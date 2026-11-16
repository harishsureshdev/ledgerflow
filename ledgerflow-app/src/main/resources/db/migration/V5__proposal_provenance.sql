-- V5: Record whether a proposal came from the AI model or the deterministic rules,
-- and why an AI answer was rejected by the guardrails (if it was).

ALTER TABLE investigator_proposals
    ADD COLUMN generated_by VARCHAR(16) NOT NULL DEFAULT 'RULES',
    ADD COLUMN guardrail_note TEXT;
