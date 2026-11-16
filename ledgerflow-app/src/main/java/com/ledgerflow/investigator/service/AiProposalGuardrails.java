package com.ledgerflow.investigator.service;

import com.ledgerflow.investigator.domain.ProposedAction;
import com.ledgerflow.recon.domain.MismatchType;

import java.util.Locale;
import java.util.Set;

/**
 * Validates an AI answer in code before it can become a proposal. The model's output is
 * treated as untrusted input: anything outside the rules is rejected and the deterministic
 * rule-based proposal is used instead.
 *
 * <p>Money is never taken from the model: the amount always comes from
 * {@link RuleBasedInvestigator#amountAtStake}.
 */
public final class AiProposalGuardrails {

    public static final String GENERATED_BY = "AI_MODEL";
    static final int MAX_RATIONALE = 2000;
    static final int MAX_ROOT_CAUSE = 64;

    /** Either an accepted draft or the reason the answer was rejected. */
    public record Verdict(ProposalDraft draft, String rejectionReason) {
        public boolean accepted() {
            return draft != null;
        }
    }

    private AiProposalGuardrails() {
    }

    public static Verdict check(AiInvestigator.AiAnswer answer, InvestigationEvidence ev) {
        if (answer == null) {
            return reject("model returned no answer");
        }

        ProposedAction action = ProposedAction.parse(answer.proposedAction());
        if (action == null) {
            return reject("unknown action '" + answer.proposedAction() + "'");
        }

        Set<ProposedAction> allowed = allowedActions(ev);
        if (!allowed.contains(action)) {
            return reject("action " + action + " is not allowed for " + describe(ev) + " (allowed: " + allowed + ")");
        }

        String rationale = answer.rationale() == null ? "" : answer.rationale().strip();
        if (rationale.isEmpty()) {
            return reject("empty rationale");
        }
        if (looksLikeCardNumber(rationale)) {
            return reject("rationale contained a card-number-like value");
        }
        if (rationale.length() > MAX_RATIONALE) {
            rationale = rationale.substring(0, MAX_RATIONALE);
        }

        double confidence = answer.confidence() == null ? 0.5 : Math.max(0.0, Math.min(1.0, answer.confidence()));

        return new Verdict(new ProposalDraft(
                normalizeRootCause(answer.rootCause()),
                action,
                RuleBasedInvestigator.amountAtStake(ev),
                rationale,
                confidence,
                GENERATED_BY,
                null), null);
    }

    /**
     * Allowed actions, narrowed by the facts: for an amount mismatch the adjustment direction
     * must match the sign of (bank - ledger), so the model cannot flip a credit into a debit.
     */
    static Set<ProposedAction> allowedActions(InvestigationEvidence ev) {
        if (ev.mismatchType() == MismatchType.AMOUNT_MISMATCH) {
            long delta = ev.delta();
            if (delta > 0) return Set.of(ProposedAction.COMPENSATING_CREDIT_SETTLEMENT, ProposedAction.MANUAL_INVESTIGATION);
            if (delta < 0) return Set.of(ProposedAction.COMPENSATING_DEBIT_SETTLEMENT, ProposedAction.MANUAL_INVESTIGATION);
            return Set.of(ProposedAction.MANUAL_INVESTIGATION);
        }
        return ProposedAction.allowedFor(ev.mismatchType());
    }

    static String normalizeRootCause(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNSPECIFIED";
        }
        String s = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "UNSPECIFIED";
        return s.length() > MAX_ROOT_CAUSE ? s.substring(0, MAX_ROOT_CAUSE) : s;
    }

    /** 13-19 consecutive digits (optionally separated by spaces/dashes) — PAN-shaped. */
    static boolean looksLikeCardNumber(String text) {
        return text.matches("(?s).*\\b(?:\\d[ -]?){12,18}\\d\\b.*");
    }

    private static String describe(InvestigationEvidence ev) {
        return ev.mismatchType() == MismatchType.AMOUNT_MISMATCH
                ? "AMOUNT_MISMATCH with delta " + ev.delta()
                : ev.mismatchType().name();
    }

    private static Verdict reject(String reason) {
        return new Verdict(null, reason);
    }
}
