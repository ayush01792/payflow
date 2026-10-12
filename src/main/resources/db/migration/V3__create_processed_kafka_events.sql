CREATE TABLE processed_kafka_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(30) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_processed_kafka_event_type CHECK (event_type IN (
        'PAYMENT_CREATED', 'PAYMENT_PROCESSING', 'PAYMENT_SUCCEEDED', 'PAYMENT_FAILED'
    ))
);
