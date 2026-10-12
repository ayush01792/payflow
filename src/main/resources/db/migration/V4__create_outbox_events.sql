CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    event_version INTEGER NOT NULL CHECK (event_version = 1),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error VARCHAR(500),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_outbox_claim CHECK (
        (status = 'CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'CLAIMED' AND claim_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT chk_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

CREATE INDEX idx_outbox_poll ON outbox_events (created_at, id)
    WHERE status IN ('PENDING', 'CLAIMED');
CREATE INDEX idx_outbox_aggregate_order ON outbox_events (aggregate_type, aggregate_id, created_at, id)
    WHERE status <> 'PUBLISHED';
