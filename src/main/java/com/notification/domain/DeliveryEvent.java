package com.notification.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "delivery_events")
public class DeliveryEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long notificationId;

    @Column(nullable = false) private String provider;   // SES, TWILIO, PUSH

    @Column(nullable = false) private String providerMessageId;

    @Column(nullable = false) private String eventType;  // DELIVERED, BOUNCED, FAILED, etc.

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @Column(nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    public DeliveryEvent() {}

    public Long getId() { return id; }
    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long notificationId) { this.notificationId = notificationId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Map<String, Object> getRawPayload() { return rawPayload; }
    public void setRawPayload(Map<String, Object> rawPayload) { this.rawPayload = rawPayload; }
    public Instant getReceivedAt() { return receivedAt; }
}
