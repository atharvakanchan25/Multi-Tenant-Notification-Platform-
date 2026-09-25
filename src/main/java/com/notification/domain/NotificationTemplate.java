package com.notification.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "notification_templates",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "template_id", "channel"}))
public class NotificationTemplate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(name = "template_id", nullable = false) private String templateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private Channel channel;

    @Column(nullable = false) private String subject;

    @Column(nullable = false, columnDefinition = "TEXT") private String body;

    public NotificationTemplate() {}

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public Channel getChannel() { return channel; }
    public void setChannel(Channel channel) { this.channel = channel; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
