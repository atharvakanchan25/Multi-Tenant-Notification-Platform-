package com.notification.service;

import com.notification.config.KafkaConfig;
import com.notification.dto.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class DlqPublisher {

    static final String DLQ_TOPIC = KafkaConfig.TOPIC_DLQ;
    private static final Logger log = LoggerFactory.getLogger(DlqPublisher.class);

    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    public DlqPublisher(KafkaTemplate<String, NotificationEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(NotificationEvent event, String reason) {
        log.error("DLQ publish notificationId={} reason={}", event.notificationId(), reason);
        kafkaTemplate.send(DLQ_TOPIC, event.tenantId(), event);
        // DLQ depth gauge is incremented by NotificationMetrics.recordDlq() in DispatchService
    }
}
