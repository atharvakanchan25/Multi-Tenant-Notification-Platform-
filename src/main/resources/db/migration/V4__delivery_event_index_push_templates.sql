-- Fast lookup of delivery events by provider message ID (used by webhook handlers)
CREATE INDEX idx_delivery_events_provider_msg_id ON delivery_events (provider_message_id);

-- Seed a PUSH template for the demo tenant so all three channels are covered
INSERT INTO notification_templates (tenant_id, template_id, channel, subject, body) VALUES
('tenant-demo', 'welcome', 'PUSH',
 'Welcome, {{name}}!',
 'Hi {{name}}, welcome to {{appName}}!'),
('tenant-demo', 'otp', 'PUSH',
 'Your OTP',
 'Your {{appName}} OTP: {{code}}. Valid 10 min.');
