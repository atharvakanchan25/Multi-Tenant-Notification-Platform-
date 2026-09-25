package com.notification.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central Micrometer metrics facade.
 *
 * Metrics exposed (all queryable at /actuator/prometheus):
 *   notification_sent_total{tenant,channel,provider}              — delivery rate
 *   notification_failed_total{tenant,channel,provider}            — failure rate
 *   notification_failover_total{channel,from_provider,to_provider}— failover events
 *   notification_dlq_total{tenant,reason}                         — DLQ publish count
 *   notification_dlq_depth                                        — live DLQ gauge
 *   notification_suppressed_total{tenant,channel}                 — opt-out suppressions
 *   notification_dispatch_seconds{tenant,channel,provider}        — dispatch latency histogram
 *     → use histogram_quantile(0.99, rate(notification_dispatch_seconds_bucket[1m]))
 */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;
    private final AtomicLong dlqDepth = new AtomicLong(0);

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Register the gauge once at construction; it reads dlqDepth on every scrape
        Gauge.builder("notification_dlq_depth", dlqDepth, AtomicLong::get)
                .description("Current number of messages in the DLQ (resets on restart)")
                .register(registry);
    }

    public void recordSent(String tenantId, String channel, String provider) {
        counter("notification_sent_total", "Total notifications successfully sent",
                tenantId, channel, provider).increment();
    }

    public void recordFailed(String tenantId, String channel, String provider) {
        counter("notification_failed_total", "Total notification send failures",
                tenantId, channel, provider).increment();
    }

    public void recordFailover(String channel, String fromProvider, String toProvider) {
        Counter.builder("notification_failover_total")
                .tag("channel", channel)
                .tag("from_provider", fromProvider)
                .tag("to_provider", toProvider)
                .description("Provider failover events")
                .register(registry)
                .increment();
    }

    public void recordDlq(String tenantId, String reason) {
        Counter.builder("notification_dlq_total")
                .tag("tenant", tenantId)
                .tag("reason", reason.length() > 50 ? reason.substring(0, 50) : reason)
                .description("Total messages published to DLQ")
                .register(registry)
                .increment();
        dlqDepth.incrementAndGet();
    }

    public void recordSuppressed(String tenantId, String channel) {
        Counter.builder("notification_suppressed_total")
                .tag("tenant", tenantId)
                .tag("channel", channel)
                .description("Notifications suppressed due to opt-out")
                .register(registry)
                .increment();
    }

    /**
     * Records dispatch latency as a histogram.
     * Grafana query: histogram_quantile(0.99, sum(rate(notification_dispatch_seconds_bucket[1m])) by (le))
     */
    public void recordDispatchLatency(String tenantId, String channel, String provider, Duration duration) {
        Timer.builder("notification_dispatch_seconds")
                .tag("tenant", tenantId)
                .tag("channel", channel)
                .tag("provider", provider)
                .description("End-to-end dispatch latency per provider call")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /** Call when a DLQ message is successfully replayed to keep the gauge accurate. */
    public void decrementDlqDepth() {
        dlqDepth.decrementAndGet();
    }

    private Counter counter(String name, String description,
                            String tenantId, String channel, String provider) {
        return Counter.builder(name)
                .tag("tenant", tenantId)
                .tag("channel", channel)
                .tag("provider", provider)
                .description(description)
                .register(registry);
    }
}
