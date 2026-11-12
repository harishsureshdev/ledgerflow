-- V4: Phase 5 Schema - Investigator Proposals (AI Generated, Human Approved)

CREATE TABLE investigator_proposals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    recon_item_id UUID NOT NULL REFERENCES recon_items(id),
    root_cause VARCHAR(64) NOT NULL,
    evidence_ids TEXT NOT NULL,
    proposed_action VARCHAR(64) NOT NULL,
    recommended_amount BIGINT,
    currency VARCHAR(3) NOT NULL,
    rationale TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_APPROVAL',
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0.95,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_investigator_proposals_recon ON investigator_proposals(recon_item_id);
