package com.ledgerflow.investigator.domain;

import com.ledgerflow.recon.domain.MismatchType;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed set of actions an investigation may propose. Defined once, here, so that
 * the rule engine, the AI guardrails and the approval path all agree on what each action does.
 *
 * <p>{@code ledgerSign} is the sign applied to the settlement clearing account when a
 * FINANCE_ADMIN approves the proposal. Zero means "approval records a decision, moves no money".
 */
public enum ProposedAction {
    REJECT_DUPLICATE_BANK_ENTRY(0),
    COMPENSATING_CREDIT_SETTLEMENT(+1),
    COMPENSATING_DEBIT_SETTLEMENT(-1),
    TRACE_PROCESSOR_SETTLEMENT_CYCLE(0),
    MANUAL_FRAUD_REVIEW(0),
    MANUAL_INVESTIGATION(0);

    private final int ledgerSign;

    ProposedAction(int ledgerSign) {
        this.ledgerSign = ledgerSign;
    }

    public boolean movesMoney() {
        return ledgerSign != 0;
    }

    public int ledgerSign() {
        return ledgerSign;
    }

    /** Actions that make sense for a given mismatch. Anything outside this set is rejected. */
    public static Set<ProposedAction> allowedFor(MismatchType type) {
        return switch (type) {
            case DUPLICATE_SETTLEMENT -> EnumSet.of(REJECT_DUPLICATE_BANK_ENTRY, MANUAL_INVESTIGATION);
            case AMOUNT_MISMATCH -> EnumSet.of(COMPENSATING_CREDIT_SETTLEMENT, COMPENSATING_DEBIT_SETTLEMENT, MANUAL_INVESTIGATION);
            case MISSING_IN_BANK -> EnumSet.of(TRACE_PROCESSOR_SETTLEMENT_CYCLE, MANUAL_INVESTIGATION);
            case MISSING_IN_LEDGER -> EnumSet.of(MANUAL_FRAUD_REVIEW, MANUAL_INVESTIGATION);
        };
    }

    public static ProposedAction parse(String value) {
        try {
            return value == null ? null : ProposedAction.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
