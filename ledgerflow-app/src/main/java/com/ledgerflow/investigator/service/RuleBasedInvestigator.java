package com.ledgerflow.investigator.service;

import com.ledgerflow.investigator.domain.ProposedAction;
import org.springframework.stereotype.Component;

/**
 * Deterministic investigator. Used when the AI model is disabled, unreachable, or its answer
 * fails the guardrails. Also the single source of truth for the money amount on every proposal:
 * amounts are always computed by code, never by the model.
 */
@Component
public class RuleBasedInvestigator {

    public static final String GENERATED_BY = "RULES";

    public ProposalDraft investigate(InvestigationEvidence ev) {
        String ref = ev.paymentReference();
        String status = ev.paymentStatus() == null ? "UNKNOWN" : ev.paymentStatus();

        return switch (ev.mismatchType()) {
            case DUPLICATE_SETTLEMENT -> new ProposalDraft(
                    "PROCESSOR_DUPLICATE_FEED",
                    ProposedAction.REJECT_DUPLICATE_BANK_ENTRY,
                    amountAtStake(ev),
                    String.format("Bank settlement feed contains multiple lines for reference %s. Internal payment "
                            + "status is %s with balanced ledger transactions. Recommend rejecting duplicate feed "
                            + "record without debiting merchant.", ref, status),
                    0.99, GENERATED_BY, null);

            case AMOUNT_MISMATCH -> {
                long delta = ev.delta();
                yield new ProposalDraft(
                        "SETTLEMENT_AMOUNT_DIVERGENCE",
                        delta > 0 ? ProposedAction.COMPENSATING_CREDIT_SETTLEMENT : ProposedAction.COMPENSATING_DEBIT_SETTLEMENT,
                        amountAtStake(ev),
                        String.format("Bank feed amount (%d %s) diverges from internal ledger payment (%d %s) by delta "
                                + "of %d minor units. Recommend posting a compensating balanced journal entry to "
                                + "reconcile processor clearing account.",
                                nz(ev.bankAmount()), ev.currency(), nz(ev.ledgerAmount()), ev.currency(), delta),
                        0.96, GENERATED_BY, null);
            }

            case MISSING_IN_BANK -> new ProposalDraft(
                    "EXTERNAL_SETTLEMENT_CYCLE_DROP",
                    ProposedAction.TRACE_PROCESSOR_SETTLEMENT_CYCLE,
                    amountAtStake(ev),
                    String.format("Payment %s is recorded and captured internally with ledger entries, but omitted "
                            + "from card processor settlement batch. Recommend verifying processor batch status "
                            + "before taking financial action.", ref),
                    0.92, GENERATED_BY, null);

            case MISSING_IN_LEDGER -> new ProposalDraft(
                    "UNMATCHED_EXTERNAL_CHARGE",
                    ProposedAction.MANUAL_FRAUD_REVIEW,
                    amountAtStake(ev),
                    String.format("Card network reported settlement line %s for %d %s, but no corresponding internal "
                            + "payment authorization or idempotency key exists. Flagged for compliance review.",
                            ref, nz(ev.bankAmount()), ev.currency()),
                    0.90, GENERATED_BY, null);
        };
    }

    /**
     * The amount involved in the mismatch, in minor units. For money-moving actions this is the
     * exact adjustment that will be posted on approval; for other actions it is informational.
     */
    public static Long amountAtStake(InvestigationEvidence ev) {
        return switch (ev.mismatchType()) {
            case DUPLICATE_SETTLEMENT -> 0L;
            case AMOUNT_MISMATCH -> Math.abs(ev.delta());
            case MISSING_IN_BANK -> ev.ledgerAmount();
            case MISSING_IN_LEDGER -> ev.bankAmount();
        };
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
