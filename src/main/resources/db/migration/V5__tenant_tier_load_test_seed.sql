-- Add priority tier to tenants; existing rows default to FREE
ALTER TABLE tenants ADD COLUMN tier VARCHAR(10) NOT NULL DEFAULT 'FREE';

-- Promote the demo tenant to PAID for load-test scenarios
UPDATE tenants SET tier = 'PAID' WHERE tenant_id = 'tenant-demo';

-- Seed 50 tenants for load testing: tenant-paid-01..25 and tenant-free-01..25
INSERT INTO tenants (tenant_id, name, sender_email, tier)
SELECT
    'tenant-paid-' || LPAD(n::text, 2, '0'),
    'Paid Tenant ' || n,
    'no-reply-paid-' || n || '@example.com',
    'PAID'
FROM generate_series(1, 25) AS n;

INSERT INTO tenants (tenant_id, name, sender_email, tier)
SELECT
    'tenant-free-' || LPAD(n::text, 2, '0'),
    'Free Tenant ' || n,
    'no-reply-free-' || n || '@example.com',
    'FREE'
FROM generate_series(1, 25) AS n;

-- Seed welcome/otp templates for all 50 load-test tenants (EMAIL channel)
INSERT INTO notification_templates (tenant_id, template_id, channel, subject, body)
SELECT t.tenant_id, 'welcome', 'EMAIL', 'Welcome, {{name}}!',
       'Hi {{name}}, welcome to {{appName}}!'
FROM tenants t
WHERE t.tenant_id LIKE 'tenant-paid-%' OR t.tenant_id LIKE 'tenant-free-%';

INSERT INTO notification_templates (tenant_id, template_id, channel, subject, body)
SELECT t.tenant_id, 'otp', 'EMAIL', 'Your OTP',
       'Your {{appName}} OTP: {{code}}. Valid 10 min.'
FROM tenants t
WHERE t.tenant_id LIKE 'tenant-paid-%' OR t.tenant_id LIKE 'tenant-free-%';
