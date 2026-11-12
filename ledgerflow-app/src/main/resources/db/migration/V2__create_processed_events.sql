-- V2: Create processed_events table for idempotent Kafka consumer deduplication

CREATE TABLE processed_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    consumer_name VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_consumer_event UNIQUE (consumer_name, event_id)
);

CREATE INDEX idx_processed_events_consumer ON processed_events(consumer_name, event_id);
