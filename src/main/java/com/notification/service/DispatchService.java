package com.notification.service;

import com.notification.adapter.ProviderAdapter;
import com.notification.adapter.SecondarySmsFallbackAdapter;
import com.notification.adapter.TwilioSmsAdapter;
import com.notification.domain.Channel;
import com.notification.domain.NotificationRequest;
import com.notification.domain.NotificationStatus;
import com.notification.domain.Tenant;
import com.notification.dto.NotificationEvent;
import com.notification.repository.NotificationRequestRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes notifications to the correct ProviderAdapter with:
 *   - Resilience4j @Retry (exp backoff + jitter) wrapping @CircuitBreaker
 *   - SMS failover: if Twilio CB is OPEN, route to SecondarySmsFallbackAdapter
 *   - Micrometer metrics (sent/failed/failover/latency) on every outcome
 *   - OpenTelemetry spans via ObservationRegistry (avoids Spring AOP self-invocation)
 *
 * NOTE on @Observed vs ObservationRegistry:
 *   @Observed on a method called from within the same bean doesn't create a span
 *   because Spring AOP proxies don't intercept self-calls.  We use
 *   ObservationRegistry.start/stop directly so every dispatch() call gets a span
 *   regardless of call site.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final String CB_NAME = "providerSend";

    private final Map<Class<? extends ProviderAdapter>, ProviderAdapter> adaptersByType;
    private final Map<Channel, ProviderAdapter> primaryAdapters;
    private final ProviderAdapter smsFallback;
    private final TemplateService templateService;
    private final DlqPublisher dlqPublisher;
    private final NotificationRequestRepository notificationRepo;
    private final NotificationMetrics metrics;
    private final CircuitBreakerRegistry cbRegistry;
    private final ObservationRegistry observationRegistry;

    public DispatchService(List<ProviderAdapter> adapterList,
                           TemplateService templateService,
                           DlqPublisher dlqPublisher,
                           NotificationRequestRepository notificationRepo,
                           NotificationMetrics metrics,
                           CircuitBreakerRegistry cbRegistry,
                           ObservationRegistry observationRegistry) {
        this.adaptersByType = adapterList.stream()
                .collect(Collectors.toMap(ProviderAdapter::getClass, Function.identity()));
        this.primaryAdapters = adapterList.stream()
                .filter(a -> !(a instanceof SecondarySmsFallbackAdapter))
                .collect(Collectors.toMap(ProviderAdapter::channel, Function.identity()));
        this.smsFallback = adaptersByType.get(SecondarySmsFallbackAdapter.class);
        this.templateService = templateService;
        this.dlqPublisher = dlqPublisher;
        this.notificationRepo = notificationRepo;
        this.metrics = metrics;
        this.cbRegistry = cbRegistry;
        this.observationRegistry = observationRegistry;
    }

    /**
     * Entry point from NotificationConsumer.
     * Creates an OTel span that covers the full dispatch lifecycle including
     * template rendering, provider call, and DB status update.
     */
    public void dispatch(NotificationEvent event, NotificationRequest record, Tenant tenant) {
        // Direct ObservationRegistry usage — works even when called from the same bean
        Observation obs = Observation.createNotStarted("notification.dispatch", observationRegistry)
                .lowCardinalityKeyValue("channel", event.channel().name())
                .lowCardinalityKeyValue("tenant_tier", tenant.getTier().name())
                .start();

        try (var ignored = obs.openScope()) {
            ProviderAdapter adapter = resolveAdapter(event.channel());
            if (adapter == null) {
                String reason = "No adapter for channel: " + event.channel();
                log.error("notificationId={} — {}", event.notificationId(), reason);
                obs.error(new IllegalStateException(reason));
                failAndDlq(event, record, reason);
                return;
            }
            try {
                sendWithResilience(event, record, tenant, adapter);
            } catch (RuntimeException | Error e) {
                log.error("All retries exhausted notificationId={}: {}",
                        event.notificationId(), sanitize(e.getMessage()));
                obs.error(e);
                failAndDlq(event, record, e.getMessage());
            }
        } finally {
            obs.stop();
        }
    }

    /**
     * For SMS: check if Twilio's CB is OPEN before the @Retry/@CircuitBreaker
     * annotations fire, so the failover is a clean redirect — not counted as a
     * Twilio failure in the CB sliding window.
     */
    private ProviderAdapter resolveAdapter(Channel channel) {
        if (channel == Channel.SMS && smsFallback != null && isCbOpen(CB_NAME)) {
            log.warn("Twilio CB is OPEN — routing SMS to fallback provider");
            metrics.recordFailover(channel.name(),
                    TwilioSmsAdapter.class.getSimpleName(),
                    SecondarySmsFallbackAdapter.PROVIDER_NAME);
            return smsFallback;
        }
        return primaryAdapters.get(channel);
    }

    private boolean isCbOpen(String name) {
        try {
            var state = cbRegistry.circuitBreaker(name).getState();
            return state == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN
                    || state == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.FORCED_OPEN;
        } catch (Exception e) {
            return false;
        }
    }

    @Retry(name = CB_NAME, fallbackMethod = "retryFallback")
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "circuitBreakerFallback")
    public void sendWithResilience(NotificationEvent event, NotificationRequest record,
                                   Tenant tenant, ProviderAdapter adapter) {
        TemplateService.RenderedMessage rendered = templateService.render(
                event.tenantId(), event.templateId(), event.channel(), event.data());

        String providerName = adapter.getClass().getSimpleName();
        Instant start = Instant.now();

        String providerMsgId = adapter.send(tenant, record, rendered.body(), rendered.subject());

        Duration latency = Duration.between(start, Instant.now());
        metrics.recordDispatchLatency(event.tenantId(), event.channel().name(), providerName, latency);
        metrics.recordSent(event.tenantId(), event.channel().name(), providerName);

        log.info("Sent notificationId={} via {} msgId={} latency={}ms",
                event.notificationId(), providerName, providerMsgId, latency.toMillis());

        record.setStatus(NotificationStatus.SENT);
        record.setUpdatedAt(Instant.now());
        notificationRepo.save(record);
    }

    public void retryFallback(NotificationEvent event, NotificationRequest record,
                              Tenant tenant, ProviderAdapter adapter, Exception e) {
        log.error("Retry exhausted notificationId={}: {}", event.notificationId(), sanitize(e.getMessage()));
        String provider = adapter != null ? adapter.getClass().getSimpleName() : "unknown";
        metrics.recordFailed(event.tenantId(), event.channel().name(), provider);
        failAndDlq(event, record, "Retries exhausted: " + e.getMessage());
    }

    public void circuitBreakerFallback(NotificationEvent event, NotificationRequest record,
                                       Tenant tenant, ProviderAdapter adapter, Exception e) {
        log.error("CB open notificationId={}: {}", event.notificationId(), sanitize(e.getMessage()));
        String provider = adapter != null ? adapter.getClass().getSimpleName() : "unknown";
        metrics.recordFailed(event.tenantId(), event.channel().name(), provider);
        failAndDlq(event, record, "Circuit open: " + e.getMessage());
    }

    /** Strips newlines/control chars from provider messages before logging to prevent log injection. */
    private static String sanitize(String msg) {
        if (msg == null) return "null";
        return msg.replaceAll("[\\r\\n\\t]", " ").replaceAll("[^\\x20-\\x7E]", "?");
    }

    private void failAndDlq(NotificationEvent event, NotificationRequest record, String reason) {
        record.setStatus(NotificationStatus.FAILED);
        record.setErrorMessage(reason);
        record.setUpdatedAt(Instant.now());
        notificationRepo.save(record);
        dlqPublisher.publish(event, reason);
        metrics.recordDlq(event.tenantId(), reason);
    }
}
