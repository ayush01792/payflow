ALTER TABLE payments DROP CONSTRAINT chk_payments_status;
ALTER TABLE payments ADD CONSTRAINT chk_payments_status CHECK(status IN ('CREATED','PROCESSING','SUCCESS','FAILED','PENDING_CONFIRMATION','PARTIALLY_REFUNDED','REFUNDED'));
CREATE TABLE refunds (
 id UUID PRIMARY KEY,payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
 amount NUMERIC(19,4) NOT NULL CHECK(amount>0),currency VARCHAR(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 status VARCHAR(30) NOT NULL CHECK(status IN ('CREATED','PROCESSING','SUCCESS','FAILED','PENDING_CONFIRMATION')),
 merchant_refund_reference VARCHAR(100) NOT NULL CHECK(length(btrim(merchant_refund_reference))>0),
 provider VARCHAR(20) NOT NULL CHECK(provider IN ('PROVIDER_A','PROVIDER_B')),provider_refund_id VARCHAR(100),
 failure_code VARCHAR(100),failure_message VARCHAR(300),owner_token UUID,lease_until TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(payment_id,merchant_refund_reference),CHECK((owner_token IS NULL)=(lease_until IS NULL))
);
CREATE INDEX refunds_payment_balance ON refunds(payment_id,status);
CREATE TABLE refund_idempotency (
 payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,idempotency_key VARCHAR(128) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,refund_id UUID NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,
 response JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(payment_id,idempotency_key)
);
CREATE TABLE processed_refund_events(event_id UUID PRIMARY KEY,event_type VARCHAR(40) NOT NULL,processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp());
CREATE TABLE refund_provider_attempts (
 id UUID PRIMARY KEY,refund_id UUID NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,
 provider VARCHAR(20) NOT NULL,provider_idempotency_key VARCHAR(100) NOT NULL,provider_refund_id VARCHAR(100),
 attempt_number INTEGER NOT NULL CHECK(attempt_number>0),status VARCHAR(30) NOT NULL CHECK(status IN ('STARTED','SUCCEEDED','DECLINED','TECHNICAL_FAILURE')),
 failure_code VARCHAR(100),failure_message VARCHAR(300),ambiguous BOOLEAN NOT NULL DEFAULT TRUE,
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),completed_at TIMESTAMPTZ,
 UNIQUE(refund_id,attempt_number),CHECK((status='STARTED')=(completed_at IS NULL))
);
CREATE INDEX refund_attempt_reference ON refund_provider_attempts(provider,provider_idempotency_key);
CREATE TABLE refund_reconciliation_tasks (
 refund_id UUID PRIMARY KEY REFERENCES refunds(id) ON DELETE CASCADE,next_reconciliation_at TIMESTAMPTZ NOT NULL,
 attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),owner_token UUID,lease_until TIMESTAMPTZ,
 escalated BOOLEAN NOT NULL DEFAULT FALSE,CHECK((owner_token IS NULL)=(lease_until IS NULL))
);
CREATE TABLE refund_reconciliation_attempts (
 id UUID PRIMARY KEY,refund_id UUID NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,provider VARCHAR(20) NOT NULL,
 attempt_number INTEGER NOT NULL,source VARCHAR(20) NOT NULL DEFAULT 'STATUS_POLL',result VARCHAR(30) NOT NULL DEFAULT 'STARTED',
 error_code VARCHAR(100),started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),completed_at TIMESTAMPTZ,UNIQUE(refund_id,attempt_number)
);
ALTER TABLE provider_webhook_events ADD COLUMN refund_id UUID REFERENCES refunds(id) ON DELETE CASCADE;
ALTER TABLE payment_reconciliation_issues ADD COLUMN refund_id UUID REFERENCES refunds(id) ON DELETE CASCADE;
CREATE TABLE payment_compensations (
 id UUID PRIMARY KEY,payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason))>0),
 refund_id UUID UNIQUE REFERENCES refunds(id) ON DELETE CASCADE,
 status VARCHAR(30) NOT NULL CHECK(status IN ('REQUESTED','REFUND_CREATED','COMPLETED','FAILED','PENDING_CONFIRMATION')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE mock_provider_refunds (
 id UUID PRIMARY KEY,provider VARCHAR(20) NOT NULL,provider_idempotency_key VARCHAR(100) NOT NULL,
 refund_id UUID NOT NULL,payment_id UUID NOT NULL,provider_transaction_id VARCHAR(100) NOT NULL,
 amount NUMERIC(19,4) NOT NULL CHECK(amount>0),currency VARCHAR(3) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,call_count INTEGER NOT NULL DEFAULT 0,result JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),UNIQUE(provider,provider_idempotency_key)
);
ALTER TABLE mock_provider_webhook_deliveries DROP CONSTRAINT mock_provider_webhook_deliveries_id_fkey;
-- The durable mock callback queue now carries both payment and refund ledger events.
ALTER TABLE mock_provider_webhook_deliveries ADD COLUMN payment_ledger_id UUID REFERENCES mock_provider_payments(id) ON DELETE CASCADE,
 ADD COLUMN refund_ledger_id UUID REFERENCES mock_provider_refunds(id) ON DELETE CASCADE;
UPDATE mock_provider_webhook_deliveries SET payment_ledger_id=id;
ALTER TABLE mock_provider_webhook_deliveries ADD CONSTRAINT chk_mock_webhook_ledger CHECK((payment_ledger_id IS NOT NULL) <> (refund_ledger_id IS NOT NULL));
