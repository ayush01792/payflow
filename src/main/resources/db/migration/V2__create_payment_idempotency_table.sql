CREATE TABLE payment_idempotency (
    id UUID PRIMARY KEY,
    merchant_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payment_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_payment_idempotency_merchant_key UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT fk_payment_idempotency_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT chk_payment_idempotency_merchant CHECK (length(btrim(merchant_id)) > 0),
    CONSTRAINT chk_payment_idempotency_key CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT chk_payment_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);
