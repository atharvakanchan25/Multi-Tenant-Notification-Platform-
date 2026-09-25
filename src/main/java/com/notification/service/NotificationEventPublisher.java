package com.notification.service;

import com.notification.config.KafkaConfig;
import com.notification.domain.Tenant;
import com.notification.dto.NotificationEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationEventPublisher {

    // Legacy constant kept so existing references compile; actual routing is tier-based
    static final String TOPIC = KafkaConfig.TOPIC_PAID;

    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    public NotificationEventPublisher(KafkaTemplate<String, NotificationEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Routes to the tier-appropriate topic.
     * Key = tenantId so all messages for a tenant land on the same partition,
     * preserving per-tenant ordering while isolating PAID from FREE throughput.
     */
    public void publish(NotificationEvent event, Tenant.Tier tier) {
        String topic = (tier == Tenant.Tier.PAID) ? KafkaConfig.TOPIC_PAID : KafkaConfig.TOPIC_FREE;
        kafkaTemplate.send(topic, event.tenantId(), event);
    }
}
