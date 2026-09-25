package com.notification.service;

import com.notification.config.KafkaConfig;
import com.notification.domain.NotificationStatus;
import com.notification.dto.NotificationEvent;
import com.notification.repository.NotificationRequestRepository;
import com.notification.repository.TenantRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final TenantRepository tenantRepo;
    private final NotificationRequestRepository notificationRepo;
    private final PreferenceService preferenceService;
    private final RateLimiterService rateLimiter;
    private final DispatchService dispatchService;
    private final NotificationMetrics metrics;
    private final ObservationRegistry observationRegistry;

    public NotificationConsumer(TenantRepository tenantRepo,
                                 NotificationRequestRepository notificationRepo,
                                 PreferenceService preferenceService,
                                 RateLimiterService rateLimiter,
                                 DispatchService dispatchService,
                                 NotificationMetrics metrics,
                                 ObservationRegistry observationRegistry) {
        this.tenantRepo = tenantRepo;
        this.notificationRepo = notificationRepo;
        this.preferenceService = preferenceService;
        this.rateLimiter = rateLimiter;
        this.dispatchService = dispatchService;
        this.metrics = metrics;
        this.observationRegistry = observationRegistry;
    }

    // NOTE: @Observed is intentionally NOT used here — @KafkaListener methods are invoked
    // directly by the Kafka container thread, bypassing the Spring AOP proxy, so the
    // annotation would never fire. ObservationRegistry is used directly instead.

    @KafkaListener(
            topics = KafkaConfig.TOPIC_PAID,
            groupId = "${spring.kafka.consumer.group-id}-paid",
            containerFactory = "paidKafkaListenerContainerFactory"
    )
    public void consumePaid(NotificationEvent event, Acknowledgment ack) {
        consume(event, ack, "PAID");
    }

    @KafkaListener(
            topics = KafkaConfig.TOPIC_FREE,
            groupId = "${spring.kafka.consumer.group-id}-free",
            containerFactory = "freeKafkaListenerContainerFactory"
    )
    public void consumeFree(NotificationEvent event, Acknowledgment ack) {
        consume(event, ack, "FREE");
    }

    private void consume(NotificationEvent event, Acknowledgment ack, String tier) {
        // 1. Per-tenant rate limit — nack with delay so Kafka re-delivers; never drop
        if (!rateLimiter.tryConsume(event.tenantId())) {
            log.warn("Rate limit exceeded tenant={} notificationId={} — requeueing",
                    event.tenantId(), event.notificationId());
            ack.nack(java.time.Duration.ofSeconds(1));
            return;
        }

        // 2. Create OTel span directly — works regardless of call site
        Observation obs = Observation.createNotStarted("notification.consume", observationRegistry)
                .lowCardinalityKeyValue("tier", tier)
                .lowCardinalityKeyValue("channel", event.channel().name())
                .start();

        try (var ignored = obs.openScope()) {
            notificationRepo.findById(event.notificationId()).ifPresent(record -> {
                // 3. Opt-out suppression check
                if (preferenceService.isSuppressed(event.tenantId(), event.recipient(), event.channel())) {
                    log.info("Suppressed notificationId={} recipient={}", event.notificationId(), event.recipient());
                    record.setStatus(NotificationStatus.SUPPRESSED);
                    record.setUpdatedAt(Instant.now());
                    notificationRepo.save(record);
                    metrics.recordSuppressed(event.tenantId(), event.channel().name());
                    return;
                }

                // 4. Resolve tenant → dispatch (CB + retry + failover inside DispatchService)
                tenantRepo.findByTenantId(event.tenantId())
                        .ifPresent(tenant -> dispatchService.dispatch(event, record, tenant));
            });

            // Only ack after successful processing — if an uncaught exception escapes
            // the ifPresent block, it will propagate out of the try block and the
            // finally clause will stop the span with an error; Kafka will redeliver.
            ack.acknowledge();
        } catch (RuntimeException e) {
            obs.error(e);
            log.error("Uncaught exception consuming notificationId={} — will redeliver: {}",
                    event.notificationId(), e.getMessage());
            ack.nack(java.time.Duration.ofSeconds(5));
        } finally {
            obs.stop();
        }
    }
}
