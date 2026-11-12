-- V3: Phase 3 Schema - Processor Events (Webhooks), Audit Log, Settlement Files & Recon Items

CREATE TABLE processor_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    processor_event_id VARCHAR(128) NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    signature VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_processor_events_event_id ON processor_events(processor_event_id);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    actor VARCHAR(64) NOT NULL,
    role VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    details TEXT,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_log_resource ON audit_log(resource_type, resource_id);
CREATE INDEX idx_audit_log_timestamp ON audit_log(timestamp);

-- Enforce Append-Only constraint on audit_log
CREATE TRIGGER trg_audit_log_immutable
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_journal_modification();

CREATE TABLE settlement_files (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    filename VARCHAR(128) NOT NULL UNIQUE,
    total_records INT NOT NULL,
    total_amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE recon_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    settlement_file_id UUID NOT NULL REFERENCES settlement_files(id),
    mismatch_type VARCHAR(32) NOT NULL, -- MISSING_IN_BANK, MISSING_IN_LEDGER, AMOUNT_MISMATCH, DUPLICATE_SETTLEMENT
    payment_reference VARCHAR(128),
    bank_amount BIGINT,
    ledger_amount BIGINT,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN', -- OPEN, RESOLVED, INVESTIGATING
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_recon_items_file ON recon_items(settlement_file_id);
CREATE INDEX idx_recon_items_mismatch ON recon_items(mismatch_type);
