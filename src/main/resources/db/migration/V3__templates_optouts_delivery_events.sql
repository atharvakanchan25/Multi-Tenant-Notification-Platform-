CREATE TABLE notification_templates (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(100) NOT NULL,
    template_id VARCHAR(100) NOT NULL,
    channel     VARCHAR(20)  NOT NULL,
    subject     VARCHAR(255) NOT NULL,
    body        TEXT         NOT NULL,
    CONSTRAINT uq_template UNIQUE (tenant_id, template_id, channel)
);

CREATE TABLE opt_outs (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(100) NOT NULL,
    recipient   VARCHAR(255) NOT NULL,
    channel     VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_optout UNIQUE (tenant_id, recipient, channel)
);

CREATE TABLE delivery_events (
    id                  BIGSERIAL PRIMARY KEY,
    notification_id     BIGINT       NOT NULL,
    provider            VARCHAR(50)  NOT NULL,
    provider_message_id VARCHAR(255) NOT NULL,
    event_type          VARCHAR(50)  NOT NULL,
    raw_payload         JSONB,
    received_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delivery_events_notification_id ON delivery_events (notification_id);

-- Seed templates for the demo tenant
INSERT INTO notification_templates (tenant_id, template_id, channel, subject, body) VALUES
('tenant-demo', 'welcome', 'EMAIL',
 'Welcome, {{name}}!',
 'Hi {{name}},\n\nWelcome to {{appName}}. Your account is ready.\n\nRegards,\nThe Team'),
('tenant-demo', 'welcome', 'SMS',
 '',
 'Hi {{name}}, welcome to {{appName}}! Reply STOP to opt out.'),
('tenant-demo', 'otp', 'EMAIL',
 'Your verification code',
 'Hi {{name}},\n\nYour OTP is: {{code}}\n\nExpires in 10 minutes.'),
('tenant-demo', 'otp', 'SMS',
 '',
 'Your {{appName}} OTP: {{code}}. Valid 10 min. Reply STOP to opt out.');
