package com.ledgerflow.investigator;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.investigator.domain.InvestigationProposal;
import com.ledgerflow.investigator.service.ReconciliationInvestigatorService;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.repository.SettlementFileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigatorEvaluationIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(InvestigatorEvaluationIntegrationTest.class);

    @Autowired
    private ReconciliationInvestigatorService investigatorService;

    @Autowired
    private SettlementFileRepository settlementFileRepository;

    @Autowired
    private ReconItemRepository reconItemRepository;

    record EvalCase(
            MismatchType mismatchType,
            Long bankAmount,
            Long ledgerAmount,
            String expectedRootCause,
            String expectedProposedAction,
            Long expectedRecommendedAmount
    ) {}

    @Test
    @DisplayName("AI Investigator Evaluation: Benchmark proposal accuracy across 32 seeded reconciliation discrepancies")
    void evaluateProposalAccuracyOnSeededDataset() {
        SettlementFile file = settlementFileRepository.save(
                new SettlementFile("eval_batch_" + UUID.randomUUID() + ".csv", 32, 100000L, "USD"));

        List<EvalCase> evalDataset = new ArrayList<>();

        // Group 1: 8 Duplicate Settlement Cases
        for (int i = 0; i < 8; i++) {
            evalDataset.add(new EvalCase(
                    MismatchType.DUPLICATE_SETTLEMENT,
                    1500L, 1500L,
                    "PROCESSOR_DUPLICATE_FEED",
                    "REJECT_DUPLICATE_BANK_ENTRY",
                    0L
            ));
        }

        // Group 2: 8 Amount Mismatch Cases (divergences)
        for (int i = 0; i < 4; i++) {
            evalDataset.add(new EvalCase(
                    MismatchType.AMOUNT_MISMATCH,
                    2500L, 2000L, // bank > ledger -> credit settlement
                    "SETTLEMENT_AMOUNT_DIVERGENCE",
                    "COMPENSATING_CREDIT_SETTLEMENT",
                    500L
            ));
        }
        for (int i = 0; i < 4; i++) {
            evalDataset.add(new EvalCase(
                    MismatchType.AMOUNT_MISMATCH,
                    1800L, 2200L, // bank < ledger -> debit settlement
                    "SETTLEMENT_AMOUNT_DIVERGENCE",
                    "COMPENSATING_DEBIT_SETTLEMENT",
                    400L
            ));
        }

        // Group 3: 8 Missing In Bank Cases
        for (int i = 0; i < 8; i++) {
            evalDataset.add(new EvalCase(
                    MismatchType.MISSING_IN_BANK,
                    null, 3500L,
                    "EXTERNAL_SETTLEMENT_CYCLE_DROP",
                    "TRACE_PROCESSOR_SETTLEMENT_CYCLE",
                    3500L
            ));
        }

        // Group 4: 8 Missing In Ledger Cases
        for (int i = 0; i < 8; i++) {
            evalDataset.add(new EvalCase(
                    MismatchType.MISSING_IN_LEDGER,
                    4200L, null,
                    "UNMATCHED_EXTERNAL_CHARGE",
                    "MANUAL_FRAUD_REVIEW",
                    4200L
            ));
        }

        assertThat(evalDataset).hasSize(32);

        int rootCauseCorrect = 0;
        int actionCorrect = 0;
        int amountCorrect = 0;
        int evidenceComplete = 0;

        for (int i = 0; i < evalDataset.size(); i++) {
            EvalCase c = evalDataset.get(i);
            ReconItem item = reconItemRepository.save(new ReconItem(
                    file,
                    c.mismatchType,
                    "eval_ref_" + i + "_" + UUID.randomUUID(),
                    c.bankAmount,
                    c.ledgerAmount,
                    "USD"
            ));

            InvestigationProposal proposal = investigatorService.investigateMismatch(item.getId());

            // Score Root Cause
            if (c.expectedRootCause.equals(proposal.rootCause())) {
                rootCauseCorrect++;
            }

            // Score Proposed Action
            if (c.expectedProposedAction.equals(proposal.proposedAction())) {
                actionCorrect++;
            }

            // Score Recommended Amount
            if (Objects.equals(c.expectedRecommendedAmount, proposal.recommendedAmount())) {
                amountCorrect++;
            }

            // Score Evidence Dossier
            if (proposal.evidenceIds() != null && !proposal.evidenceIds().isEmpty()) {
                evidenceComplete++;
            }
        }

        double rootCauseAccuracy = (double) rootCauseCorrect / evalDataset.size();
        double actionAccuracy = (double) actionCorrect / evalDataset.size();
        double amountAccuracy = (double) amountCorrect / evalDataset.size();
        double evidenceCompleteness = (double) evidenceComplete / evalDataset.size();

        log.info("=== EVALUATION BENCHMARK RESULTS (N=32) ===");
        log.info("Root Cause Accuracy:      {}/{} ({:.1f}%)", rootCauseCorrect, evalDataset.size(), rootCauseAccuracy * 100);
        log.info("Action Accuracy:          {}/{} ({:.1f}%)", actionCorrect, evalDataset.size(), actionAccuracy * 100);
        log.info("Amount Accuracy:          {}/{} ({:.1f}%)", amountCorrect, evalDataset.size(), amountAccuracy * 100);
        log.info("Evidence Completeness:    {}/{} ({:.1f}%)", evidenceComplete, evalDataset.size(), evidenceCompleteness * 100);

        // Core Requirement: Proposal accuracy is measured and achieves 100% on evaluation set
        assertThat(rootCauseAccuracy).isEqualTo(1.0);
        assertThat(actionAccuracy).isEqualTo(1.0);
        assertThat(amountAccuracy).isEqualTo(1.0);
        assertThat(evidenceCompleteness).isEqualTo(1.0);
    }
}
