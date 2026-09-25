CREATE TABLE tenants (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    sender_email VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE notification_requests (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(100) NOT NULL,
    channel       VARCHAR(20)  NOT NULL,
    recipient     VARCHAR(255) NOT NULL,
    template_id   VARCHAR(100) NOT NULL,
    data          JSONB,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Seed a default tenant for local testing
INSERT INTO tenants (tenant_id, name, sender_email)
VALUES ('tenant-demo', 'Demo Tenant', 'no-reply@example.com');
