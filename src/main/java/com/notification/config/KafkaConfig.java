package com.notification.config;

import com.notification.dto.NotificationEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

@Configuration
public class KafkaConfig {

    // Topic names — referenced by publisher and consumers
    public static final String TOPIC_PAID = "notification-requests-paid";
    public static final String TOPIC_FREE = "notification-requests-free";
    public static final String TOPIC_DLQ  = "dlq-notifications";

    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;
    @Value("${spring.kafka.consumer.group-id}") private String groupId;

    // ── Topics ───────────────────────────────────────────────────────────────
    // PAID: 4 partitions → 4 consumer threads → lower latency for paying customers
    // FREE: 2 partitions → 2 consumer threads → fair share, can't starve PAID
    // DLQ:  3 partitions → enough parallelism for replay without over-provisioning

    @Bean public NewTopic paidTopic() {
        return TopicBuilder.name(TOPIC_PAID).partitions(4).replicas(1).build();
    }

    @Bean public NewTopic freeTopic() {
        return TopicBuilder.name(TOPIC_FREE).partitions(2).replicas(1).build();
    }

    @Bean public NewTopic dlqTopic() {
        return TopicBuilder.name(TOPIC_DLQ).partitions(3).replicas(1).build();
    }

    // ── Producer ─────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, NotificationEvent> producerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true
        ));
    }

    @Bean
    public KafkaTemplate<String, NotificationEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Consumer factories ───────────────────────────────────────────────────
    // Two separate factories so PAID and FREE listeners have independent thread pools.
    // A slow FREE tenant cannot block a PAID consumer thread.

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> paidKafkaListenerContainerFactory() {
        return buildFactory(groupId + "-paid", 4);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> freeKafkaListenerContainerFactory() {
        return buildFactory(groupId + "-free", 2);
    }

    private ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> buildFactory(
            String consumerGroupId, int concurrency) {
        JsonDeserializer<NotificationEvent> deserializer =
                new JsonDeserializer<>(NotificationEvent.class, false);
        ConsumerFactory<String, NotificationEvent> cf = new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                // Limit max records per poll so one bursty tenant can't monopolise a thread
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10"
        ), new StringDeserializer(), deserializer);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, NotificationEvent>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
