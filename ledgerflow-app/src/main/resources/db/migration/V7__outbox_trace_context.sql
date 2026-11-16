-- V7: Carry the W3C trace context of the request that created an outbox event, so the relay's
-- later Kafka publish (in a different thread, maybe a different instance) joins the same trace.
ALTER TABLE outbox ADD COLUMN trace_parent VARCHAR(128);
