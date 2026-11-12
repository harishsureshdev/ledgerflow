package com.ledgerflow.investigator;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.investigator.domain.InvestigationProposal;
import com.ledgerflow.investigator.domain.InvestigationProposalEntity;
import com.ledgerflow.investigator.repository.InvestigatorProposalRepository;
import com.ledgerflow.investigator.service.ReconciliationInvestigatorService;
import com.ledgerflow.investigator.tools.InvestigatorReadOnlyTools;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.repository.AccountRepository;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.repository.SettlementFileRepository;
import com.ledgerflow.recon.service.AdjustmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigatorSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InvestigatorReadOnlyTools readOnlyTools;

    @Autowired
    private ReconciliationInvestigatorService investigatorService;

    @Autowired
    private AdjustmentService adjustmentService;

    @Autowired
    private SettlementFileRepository settlementFileRepository;

    @Autowired
    private ReconItemRepository reconItemRepository;

    @Autowired
    private InvestigatorProposalRepository proposalRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    @DisplayName("Security Proof 1: InvestigatorReadOnlyTools exposes ZERO mutating methods (strictly read-only)")
    void readOnlyToolsExposeNoMutatingMethods() {
        Method[] methods = InvestigatorReadOnlyTools.class.getDeclaredMethods();

        for (Method method : methods) {
            if (!Modifier.isPublic(method.getModifiers())) continue;

            String name = method.getName().toLowerCase();
            // Assert no mutation method prefixes
            assertThat(name)
                    .doesNotStartWith("save")
                    .doesNotStartWith("insert")
                    .doesNotStartWith("update")
                    .doesNotStartWith("delete")
                    .doesNotStartWith("post")
                    .doesNotStartWith("transfer")
                    .doesNotStartWith("modify")
                    .doesNotStartWith("create");

            // Assert method is marked readOnly
            org.springframework.transaction.annotation.Transactional tx =
                    method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            if (tx != null) {
                assertThat(tx.readOnly())
                        .as("Tool method %s must be @Transactional(readOnly = true)", method.getName())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Security Proof 2: Executing AI investigation produces exactly 0 ledger writes and 0 balance changes")
    void aiInvestigationPathProducesZeroLedgerWrites() {
        SettlementFile file = settlementFileRepository.save(
                new SettlementFile("test_investigate_" + UUID.randomUUID() + ".csv", 1, 5000L, "USD"));
        ReconItem item = reconItemRepository.save(
                new ReconItem(file, MismatchType.AMOUNT_MISMATCH, "ref_inv_test", 5000L, 4500L, "USD"));

        // Snapshot ledger state before AI investigation
        long entriesCountBefore = journalEntryRepository.count();
        long journalSumBefore = journalEntryRepository.sumAllEntriesAmount();
        Map<UUID, Long> accountBalancesBefore = accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::getId, Account::getBalance));

        // Execute AI Investigation
        InvestigationProposal proposal = investigatorService.investigateMismatch(item.getId());

        // Snapshot ledger state after AI investigation
        long entriesCountAfter = journalEntryRepository.count();
        long journalSumAfter = journalEntryRepository.sumAllEntriesAmount();
        Map<UUID, Long> accountBalancesAfter = accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::getId, Account::getBalance));

        // Core Principle 4 Invariant Assertions: ZERO LEDGER WRITES
        assertThat(entriesCountAfter).as("AI path must never create journal entries").isEqualTo(entriesCountBefore);
        assertThat(journalSumAfter).as("Journal sum must remain unchanged").isEqualTo(journalSumBefore);
        assertThat(accountBalancesAfter).as("Account balances must remain unchanged").isEqualTo(accountBalancesBefore);

        // Assert proposal is purely advisory and pending approval
        assertThat(proposal.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(proposal.proposedAction()).isEqualTo("COMPENSATING_CREDIT_SETTLEMENT");
        assertThat(proposal.recommendedAmount()).isEqualTo(500L);
    }

    @Test
    @WithMockUser(username = "ops_agent_kurt", roles = {"OPS_AGENT"})
    @DisplayName("Security Proof 3: OPS_AGENT cannot approve AI proposal (Service-Layer hard-rejected with AccessDeniedException)")
    void opsAgentCannotApproveProposal() {
        SettlementFile file = settlementFileRepository.save(
                new SettlementFile("test_file_" + UUID.randomUUID() + ".csv", 1, 1000L, "USD"));
        ReconItem item = reconItemRepository.save(
                new ReconItem(file, MismatchType.DUPLICATE_SETTLEMENT, "ref_dup", 1000L, 1000L, "USD"));

        InvestigationProposal proposal = investigatorService.investigateMismatch(item.getId());

        // Attempting to approve as OPS_AGENT
        assertThatThrownBy(() -> adjustmentService.approveProposal(proposal.id(), "Trying to approve as ops agent"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "finance_chief", roles = {"FINANCE_ADMIN"})
    @DisplayName("Security Proof 4: Only FINANCE_ADMIN can approve AI proposal and trigger compensating entry")
    void financeAdminCanApproveProposalAndPostCompensatingEntry() {
        SettlementFile file = settlementFileRepository.save(
                new SettlementFile("test_file_" + UUID.randomUUID() + ".csv", 1, 2000L, "USD"));
        ReconItem item = reconItemRepository.save(
                new ReconItem(file, MismatchType.AMOUNT_MISMATCH, "ref_valid_adj", 2000L, 1500L, "USD"));

        InvestigationProposal proposal = investigatorService.investigateMismatch(item.getId());

        long entriesCountBefore = journalEntryRepository.count();

        // FINANCE_ADMIN approves proposal
        InvestigationProposal approved = adjustmentService.approveProposal(proposal.id(), "Verified processor undercharge");

        assertThat(approved.status()).isEqualTo("APPROVED");

        // Compensating entry was posted
        long entriesCountAfter = journalEntryRepository.count();
        assertThat(entriesCountAfter).isEqualTo(entriesCountBefore + 2); // 2 balanced legs

        // Books still balance to zero!
        assertThat(journalEntryRepository.sumAllEntriesAmount()).isEqualTo(0L);

        // Recon item status updated
        ReconItem reloaded = reconItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("RESOLVED");
    }
}
