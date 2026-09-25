package com.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tenants")
public class Tenant {

    public enum Tier { PAID, FREE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String senderEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tier tier = Tier.FREE;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Tenant() {}

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }
    public Tier getTier() { return tier; }
    public void setTier(Tier tier) { this.tier = tier; }
    public Instant getCreatedAt() { return createdAt; }
}
