package com.ledgerflow.investigator.service;

import com.ledgerflow.investigator.domain.ProposedAction;

/**
 * A proposal before it is persisted.
 *
 * @param generatedBy   {@code AI_MODEL} or {@code RULES}
 * @param guardrailNote why an AI answer was rejected and the rules were used instead (null otherwise)
 */
public record ProposalDraft(
        String rootCause,
        ProposedAction action,
        Long recommendedAmount,
        String rationale,
        double confidence,
        String generatedBy,
        String guardrailNote
) {
    public ProposalDraft withGuardrailNote(String note) {
        return new ProposalDraft(rootCause, action, recommendedAmount, rationale, confidence, generatedBy, note);
    }
}
