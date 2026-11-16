-- V8: At most one payment per idempotency claim. Backstop for the lease-takeover race: if a slow
-- original request and a retry that took over its expired lease both reach the DB write, the
-- second insert fails here and the service returns the first payment instead.
CREATE UNIQUE INDEX uq_payments_idempotency_key_id ON payments(idempotency_key_id) WHERE idempotency_key_id IS NOT NULL;
