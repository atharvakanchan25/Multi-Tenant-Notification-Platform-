ALTER TABLE notification_requests
    ADD COLUMN idempotency_key VARCHAR(64) UNIQUE;
