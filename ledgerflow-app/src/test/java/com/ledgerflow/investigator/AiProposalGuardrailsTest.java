package com.ledgerflow.investigator;

import com.ledgerflow.investigator.domain.ProposedAction;
import com.ledgerflow.investigator.service.AiInvestigator.AiAnswer;
import com.ledgerflow.investigator.service.AiProposalGuardrails;
import com.ledgerflow.investigator.service.InvestigationEvidence;
import com.ledgerflow.recon.domain.MismatchType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiProposalGuardrailsTest {

    private static InvestigationEvidence ev(MismatchType t, Long bank, Long ledger) {
        return new InvestigationEvidence(UUID.randomUUID(), UUID.randomUUID(), t, "ref", bank, ledger, "USD",
                null, 0, 0, List.of("recon_item:x"));
    }

    private static AiAnswer answer(ProposedAction a) {
        return new AiAnswer("cause", a.name(), "because", 0.7);
    }

    @ParameterizedTest
    @EnumSource(ProposedAction.class)
    @DisplayName("No money-moving action is ever accepted for a duplicate, missing-in-bank or missing-in-ledger item")
    void moneyMovingActionsOnlyForAmountMismatch(ProposedAction action) {
        for (MismatchType t : List.of(MismatchType.DUPLICATE_SETTLEMENT, MismatchType.MISSING_IN_BANK, MismatchType.MISSING_IN_LEDGER)) {
            var verdict = AiProposalGuardrails.check(answer(action), ev(t, 100L, 100L));
            if (action.movesMoney()) {
                assertThat(verdict.accepted()).as("%s for %s", action, t).isFalse();
            }
        }
    }

    @Test
    @DisplayName("Zero-delta amount mismatch: only manual investigation is allowed")
    void zeroDeltaAllowsOnlyManual() {
        var e = ev(MismatchType.AMOUNT_MISMATCH, 100L, 100L);
        assertThat(AiProposalGuardrails.check(answer(ProposedAction.COMPENSATING_CREDIT_SETTLEMENT), e).accepted()).isFalse();
        assertThat(AiProposalGuardrails.check(answer(ProposedAction.MANUAL_INVESTIGATION), e).accepted()).isTrue();
    }

    @Test
    @DisplayName("Confidence is clamped to [0, 1] and root cause normalised to a 64-char label")
    void normalisation() {
        var verdict = AiProposalGuardrails.check(
                new AiAnswer("x".repeat(200), "MANUAL_INVESTIGATION", "ok", 7.0),
                ev(MismatchType.DUPLICATE_SETTLEMENT, 1L, 1L));
        assertThat(verdict.draft().confidence()).isEqualTo(1.0);
        assertThat(verdict.draft().rootCause()).hasSize(64).matches("[A-Z0-9_]+");
    }

    @Test
    @DisplayName("Amounts are shown to the model in major units so it cannot read cents as dollars")
    void amountsAreDescribedInMajorUnits() {
        assertThat(com.ledgerflow.investigator.service.AiInvestigator.describeAmount(4700L, "USD"))
                .isEqualTo("47.00 USD (4700 minor units)");
        assertThat(com.ledgerflow.investigator.service.AiInvestigator.describeAmount(500L, "JPY"))
                .isEqualTo("500 JPY (500 minor units)");
        assertThat(com.ledgerflow.investigator.service.AiInvestigator.describeAmount(null, "USD")).isEqualTo("none");
    }

    @Test
    @DisplayName("Null fields are rejected or defaulted, never crash")
    void nulls() {
        assertThat(AiProposalGuardrails.check(null, ev(MismatchType.DUPLICATE_SETTLEMENT, 1L, 1L)).accepted()).isFalse();
        assertThat(AiProposalGuardrails.check(new AiAnswer(null, null, null, null),
                ev(MismatchType.DUPLICATE_SETTLEMENT, 1L, 1L)).accepted()).isFalse();
    }
}
