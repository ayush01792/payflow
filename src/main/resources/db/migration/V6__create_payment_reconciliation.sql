ALTER TABLE mock_provider_payments ADD COLUMN amount NUMERIC(19,4), ADD COLUMN currency VARCHAR(3);
UPDATE mock_provider_payments m SET amount=p.amount,currency=p.currency FROM payments p WHERE p.id=m.payment_id;
CREATE TABLE provider_webhook_events (
 id UUID PRIMARY KEY, provider VARCHAR(20) NOT NULL CHECK(provider IN ('PROVIDER_A','PROVIDER_B')),
 provider_event_id VARCHAR(100) NOT NULL, provider_transaction_id VARCHAR(100) NOT NULL,
 provider_reference VARCHAR(100) NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 payment_id UUID REFERENCES payments(id) ON DELETE CASCADE,
 received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), processed_at TIMESTAMPTZ,
 status VARCHAR(30) NOT NULL CHECK(status IN ('RECEIVED','APPLIED','CONFIRMED','CONFLICT','UNMATCHED','PENDING')),
 UNIQUE(provider,provider_event_id)
);
CREATE TABLE payment_reconciliation_tasks (
 payment_id UUID PRIMARY KEY REFERENCES payments(id) ON DELETE CASCADE,
 next_reconciliation_at TIMESTAMPTZ NOT NULL,
 attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
 owner_token UUID, lease_until TIMESTAMPTZ,
 escalated BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK((owner_token IS NULL)=(lease_until IS NULL))
);
CREATE INDEX reconciliation_due ON payment_reconciliation_tasks(next_reconciliation_at) WHERE NOT escalated;
CREATE TABLE payment_reconciliation_attempts (
 id UUID PRIMARY KEY, payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
 provider VARCHAR(20) NOT NULL CHECK(provider IN ('PROVIDER_A','PROVIDER_B')),
 attempt_number INTEGER NOT NULL CHECK(attempt_number>0), source VARCHAR(20) NOT NULL DEFAULT 'STATUS_POLL',
 result VARCHAR(30) NOT NULL DEFAULT 'STARTED', error_code VARCHAR(100),
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ,
 UNIQUE(payment_id,attempt_number)
);
CREATE TABLE payment_reconciliation_issues (
 id UUID PRIMARY KEY, payment_id UUID REFERENCES payments(id) ON DELETE CASCADE,
 provider VARCHAR(20) NOT NULL, existing_status VARCHAR(30), reported_status VARCHAR(30),
 source VARCHAR(20) NOT NULL, source_reference VARCHAR(100) NOT NULL, reason VARCHAR(100) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), resolution_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 UNIQUE(source,provider,source_reference,reason)
);
CREATE TABLE mock_provider_webhook_deliveries (
 id UUID PRIMARY KEY REFERENCES mock_provider_payments(id) ON DELETE CASCADE,
 provider VARCHAR(20) NOT NULL, payload TEXT NOT NULL,
 next_attempt_at TIMESTAMPTZ NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0,
 owner_token UUID, lease_until TIMESTAMPTZ, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 CHECK((owner_token IS NULL)=(lease_until IS NULL))
);
CREATE INDEX mock_webhooks_due ON mock_provider_webhook_deliveries(next_attempt_at) WHERE status='PENDING';
CREATE INDEX provider_attempt_reference ON payment_provider_attempts(provider,provider_idempotency_key);
-- Existing uncertain payments become eligible after the initial default delay on upgrade.
INSERT INTO payment_reconciliation_tasks(payment_id,next_reconciliation_at)
SELECT id,clock_timestamp()+INTERVAL '10 seconds' FROM payments WHERE status='PENDING_CONFIRMATION';
