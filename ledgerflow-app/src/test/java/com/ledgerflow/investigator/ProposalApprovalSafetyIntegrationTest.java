package com.ledgerflow.investigator;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.investigator.domain.InvestigationProposal;
import com.ledgerflow.investigator.service.ReconciliationInvestigatorService;
import com.ledgerflow.ledger.repository.AccountRepository;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.repository.SettlementFileRepository;
import com.ledgerflow.recon.service.AdjustmentService;
import com.ledgerflow.recon.service.ProposalNotApprovableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for bugs found in the proposal approval path.
 * Each bug was reproduced by these tests before the fix.
 */
class ProposalApprovalSafetyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ReconciliationInvestigatorService investigatorService;
    @Autowired private AdjustmentService adjustmentService;
    @Autowired private SettlementFileRepository settlementFileRepository;
    @Autowired private ReconItemRepository reconItemRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private AccountRepository accountRepository;

    @AfterEach
    void assertLedgerInvariants() {
        assertThat(journalEntryRepository.sumAllEntriesAmount())
                .as("journal must sum to zero").isZero();
        accountRepository.findAll().forEach(a -> {
            if (!a.isAllowNegative()) {
                assertThat(a.getBalance()).as("account %s below minimum", a.getAccountNumber()).isNotNegative();
            }
        });
    }

    private ReconItem reconItem(MismatchType type, Long bank, Long ledger) {
        SettlementFile file = settlementFileRepository.save(
                new SettlementFile("safety_" + UUID.randomUUID() + ".csv", 1, bank == null ? 0L : bank, "USD"));
        return reconItemRepository.save(
                new ReconItem(file, type, "ref_" + UUID.randomUUID(), bank, ledger, "USD"));
    }

    @Test
    @WithMockUser(username = "finance_chief", roles = {"FINANCE_ADMIN"})
    @DisplayName("Bug: approving the same proposal twice must not post the adjustment twice")
    void doubleApprovalIsRejected() {
        ReconItem item = reconItem(MismatchType.AMOUNT_MISMATCH, 2000L, 1500L);
        InvestigationProposal proposal = investigatorService.investigateMismatch(item.getId());

        adjustmentService.approveProposal(proposal.id(), "first");
        long entriesAfterFirst = journalEntryRepository.count();

        assertThatThrownBy(() -> adjustmentService.approveProposal(proposal.id(), "second"))
                .isInstanceOf(ProposalNotApprovableException.class);
        assertThat(journalEntryRepository.count()).isEqualTo(entriesAfterFirst);
    }

    @Test
    @DisplayName("Bug: 20 concurrent approvals of one proposal post the adjustment exactly once")
    void concurrentApprovalsPostOnce() throws Exception {
        ReconItem item = reconItem(MismatchType.AMOUNT_MISMATCH, 3000L, 2000L);
        InvestigationProposal proposal = investigatorService.investigateMismatch(item.getId());
        long entriesBefore = journalEntryRepository.count();

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        var admin = new UsernamePasswordAuthenticationToken("finance_chief", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN")));

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(admin);
                    start.await();
                    adjustmentService.approveProposal(proposal.id(), "race");
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // losers are expected to be rejected
                } finally {
                    SecurityContextHolder.clearContext();
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(journalEntryRepository.count()).isEqualTo(entriesBefore + 2);
    }

    @Test
    @WithMockUser(username = "finance_chief", roles = {"FINANCE_ADMIN"})
    @DisplayName("Bug: approving an investigate-only proposal (MISSING_IN_BANK) must not move money")
    void investigateOnlyProposalMovesNoMoney() {
        ReconItem item = reconItem(MismatchType.MISSING_IN_BANK, null, 4200L);
        InvestigationProposal proposal = investigatorService.investigateMismatch(item.getId());
        long entriesBefore = journalEntryRepository.count();

        InvestigationProposal approved = adjustmentService.approveProposal(proposal.id(), "trace it");

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(journalEntryRepository.count()).isEqualTo(entriesBefore);
    }

    @Test
    @WithMockUser(username = "finance_chief", roles = {"FINANCE_ADMIN"})
    @DisplayName("Bug: approving a fraud-review proposal (MISSING_IN_LEDGER) must not move money")
    void fraudReviewProposalMovesNoMoney() {
        ReconItem item = reconItem(MismatchType.MISSING_IN_LEDGER, 9900L, null);
        InvestigationProposal proposal = investigatorService.investigateMismatch(item.getId());
        long entriesBefore = journalEntryRepository.count();

        adjustmentService.approveProposal(proposal.id(), "send to compliance");

        assertThat(journalEntryRepository.count()).isEqualTo(entriesBefore);
    }

    @Test
    @WithMockUser(username = "finance_chief", roles = {"FINANCE_ADMIN"})
    @DisplayName("Bug: a bank under-settlement adjusts the clearing account in the opposite direction to an over-settlement")
    void adjustmentDirectionFollowsDelta() {
        ReconItem over = reconItem(MismatchType.AMOUNT_MISMATCH, 2000L, 1500L);   // bank +500
        ReconItem under = reconItem(MismatchType.AMOUNT_MISMATCH, 1500L, 2000L);  // bank -500

        long clearingStart = clearingBalance();
        adjustmentService.approveProposal(investigatorService.investigateMismatch(over.getId()).id(), "over");
        long afterOver = clearingBalance();
        adjustmentService.approveProposal(investigatorService.investigateMismatch(under.getId()).id(), "under");
        long afterUnder = clearingBalance();

        assertThat(afterOver - clearingStart).isEqualTo(500L);
        assertThat(afterUnder - afterOver).isEqualTo(-500L);
    }

    private long clearingBalance() {
        return accountRepository.findByAccountNumber("SETTLEMENT-CLEARING-USD")
                .map(a -> a.getBalance()).orElse(0L);
    }
}
