ALTER TABLE payments DROP CONSTRAINT chk_payments_status;
ALTER TABLE payments ALTER COLUMN status TYPE VARCHAR(30);
ALTER TABLE payments ADD CONSTRAINT chk_payments_status
    CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCESS', 'FAILED', 'PENDING_CONFIRMATION'));
ALTER TABLE payments ADD COLUMN provider VARCHAR(20);
ALTER TABLE payments ADD COLUMN provider_transaction_id VARCHAR(100);
ALTER TABLE payments ADD COLUMN failure_code VARCHAR(100);
ALTER TABLE payments ADD COLUMN orchestration_provider VARCHAR(20);
ALTER TABLE payments ADD COLUMN orchestration_token UUID;
ALTER TABLE payments ADD COLUMN orchestration_lease_until TIMESTAMPTZ;
ALTER TABLE payments ADD CONSTRAINT chk_payment_orchestration_lease
    CHECK ((orchestration_token IS NULL) = (orchestration_lease_until IS NULL));

CREATE TABLE payment_provider_attempts (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    source_event_id UUID NOT NULL,
    provider VARCHAR(20) NOT NULL CHECK (provider IN ('PROVIDER_A', 'PROVIDER_B')),
    provider_idempotency_key VARCHAR(100) NOT NULL,
    provider_transaction_id VARCHAR(100),
    status VARCHAR(30) NOT NULL CHECK (status IN ('STARTED', 'SUCCEEDED', 'DECLINED', 'TECHNICAL_FAILURE')),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    failure_code VARCHAR(100),
    failure_message VARCHAR(300),
    response_ambiguous BOOLEAN NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_provider_attempt_number UNIQUE (payment_id, attempt_number),
    CONSTRAINT chk_provider_attempt_completion CHECK ((status = 'STARTED') = (completed_at IS NULL))
);
CREATE INDEX idx_provider_attempt_event ON payment_provider_attempts(source_event_id);

-- Demo-only provider state is durable and distinct from PayFlow's attempt audit.
CREATE TABLE mock_provider_payments (
    id UUID PRIMARY KEY,
    provider VARCHAR(20) NOT NULL CHECK (provider IN ('PROVIDER_A', 'PROVIDER_B')),
    provider_idempotency_key VARCHAR(100) NOT NULL,
    payment_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    call_count INTEGER NOT NULL DEFAULT 0 CHECK (call_count >= 0),
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mock_provider_key UNIQUE (provider, provider_idempotency_key),
    CONSTRAINT chk_mock_provider_result CHECK (result IS NULL OR jsonb_typeof(result) = 'object')
);
