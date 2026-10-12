CREATE TABLE payments (
    id UUID PRIMARY KEY,
    merchant_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_payments_merchant_id CHECK (length(btrim(merchant_id)) > 0),
    CONSTRAINT chk_payments_user_id CHECK (length(btrim(user_id)) > 0),
    CONSTRAINT chk_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_payments_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_payments_payment_method CHECK (payment_method IN ('UPI', 'CARD', 'NET_BANKING', 'WALLET')),
    CONSTRAINT chk_payments_status CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX idx_payments_merchant_id ON payments (merchant_id);
CREATE INDEX idx_payments_user_id ON payments (user_id);
CREATE INDEX idx_payments_created_at ON payments (created_at);
