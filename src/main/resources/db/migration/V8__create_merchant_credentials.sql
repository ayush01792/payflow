CREATE TABLE merchants (
 id UUID PRIMARY KEY,
 external_merchant_id VARCHAR(100) NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('ACTIVE','SUSPENDED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Preserve existing business identifiers. Legacy accounts start suspended with no credentials.
INSERT INTO merchants(id,external_merchant_id,name,status)
 SELECT gen_random_uuid(),merchant_id,merchant_id,'SUSPENDED' FROM payments GROUP BY merchant_id;
CREATE TABLE merchant_api_keys (
 id UUID PRIMARY KEY,
 merchant_id UUID NOT NULL REFERENCES merchants(id),
 key_prefix VARCHAR(22) NOT NULL UNIQUE,
 secret_hash VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 expires_at TIMESTAMPTZ,
 last_used_at TIMESTAMPTZ,
 revoked_at TIMESTAMPTZ,
 CHECK ((status='ACTIVE' AND revoked_at IS NULL) OR (status='REVOKED' AND revoked_at IS NOT NULL))
);
CREATE INDEX idx_payments_merchant_created ON payments(merchant_id,created_at,id);
CREATE INDEX idx_merchant_keys_merchant ON merchant_api_keys(merchant_id);
