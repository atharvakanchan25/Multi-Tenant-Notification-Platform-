package com.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "opt_outs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "recipient", "channel"}))
public class OptOut {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(nullable = false) private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private Channel channel;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public OptOut() {}

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public Channel getChannel() { return channel; }
    public void setChannel(Channel channel) { this.channel = channel; }
    public Instant getCreatedAt() { return createdAt; }
}
