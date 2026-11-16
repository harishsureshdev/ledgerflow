package com.ledgerflow.investigator;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.investigator.domain.InvestigationProposal;
import com.ledgerflow.investigator.service.ReconciliationInvestigatorService;
import com.ledgerflow.investigator.tools.InvestigatorReadOnlyTools;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.service.PaymentService;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.repository.SettlementFileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug found in a live run: the ledger-lookup tool searched journal transactions by the bare
 * payment id, but payments are journaled under "PAYMENT-<id>", so it never found anything and
 * the AI reported "no ledger entries" for every real payment.
 */
class InvestigatorEvidenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private InvestigatorReadOnlyTools tools;
    @Autowired private ReconciliationInvestigatorService investigatorService;
    @Autowired private SettlementFileRepository settlementFileRepository;
    @Autowired private ReconItemRepository reconItemRepository;

    @Test
    @DisplayName("Investigating a real payment's mismatch includes its two journal entries as evidence")
    void ledgerEntriesAreFoundForARealPayment() {
        UUID paymentId = paymentService.processPayment("merch_" + UUID.randomUUID(), "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_" + UUID.randomUUID(), 4200L, "USD", "tok_visa_4242")).response().id();

        assertThat(tools.getLedgerEntries(paymentId.toString())).hasSize(2);

        SettlementFile file = settlementFileRepository.save(new SettlementFile("ev_" + UUID.randomUUID() + ".csv", 1, 4700L, "USD"));
        ReconItem item = reconItemRepository.save(new ReconItem(file, MismatchType.AMOUNT_MISMATCH, paymentId.toString(), 4700L, 4200L, "USD"));

        InvestigationProposal p = investigatorService.investigateMismatch(item.getId());
        assertThat(p.evidenceIds()).filteredOn(e -> e.startsWith("journal_entry:")).hasSize(2);
        assertThat(p.evidenceIds()).contains("payment:" + paymentId);
    }
}
