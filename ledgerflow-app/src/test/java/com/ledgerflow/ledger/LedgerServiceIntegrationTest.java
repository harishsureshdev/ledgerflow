package com.ledgerflow.ledger;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.common.domain.Money;
import com.ledgerflow.ledger.domain.*;
import com.ledgerflow.ledger.exception.InsufficientFundsException;
import com.ledgerflow.ledger.exception.UnbalancedJournalException;
import com.ledgerflow.ledger.repository.AccountRepository;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.ledger.repository.JournalTransactionRepository;
import com.ledgerflow.ledger.service.LedgerService;
import com.ledgerflow.ledger.service.PostTransactionCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalTransactionRepository transactionRepository;

    @Autowired
    private JournalEntryRepository entryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account customerAccount;
    private Account merchantAccount;

    @BeforeEach
    void setUp() {
        customerAccount = ledgerService.createAccount("CUST-" + UUID.randomUUID(), AccountType.CUSTOMER, "USD", false);
        merchantAccount = ledgerService.createAccount("MERCH-" + UUID.randomUUID(), AccountType.MERCHANT, "USD", true);
    }

    @AfterEach
    void verifyInvariant() {
        // Core design principle: Money is never created or destroyed.
        ledgerService.assertJournalSumsToZero();
    }

    @Test
    @DisplayName("Successfully posts balanced double-entry journal transaction")
    void postsBalancedTransaction() {
        // Fund customer account first via a balanced settlement transaction
        Account settlement = ledgerService.createAccount("SETTLE-" + UUID.randomUUID(), AccountType.SETTLEMENT, "USD", true);
        ledgerService.postTransaction(new PostTransactionCommand(
                "FUND-" + UUID.randomUUID(),
                TransactionType.SETTLEMENT,
                "Initial funding",
                List.of(
                        new PostTransactionCommand.PostEntryCommand(customerAccount.getId(), Money.of(10000L, "USD")),
                        new PostTransactionCommand.PostEntryCommand(settlement.getId(), Money.of(-10000L, "USD"))
                )
        ));

        // Now move 2500 cents ($25.00) from customer to merchant
        String ref = "TX-" + UUID.randomUUID();
        PostTransactionCommand command = new PostTransactionCommand(
                ref,
                TransactionType.PAYMENT_CAPTURE,
                "Payment test",
                List.of(
                        new PostTransactionCommand.PostEntryCommand(customerAccount.getId(), Money.of(-2500L, "USD")),
                        new PostTransactionCommand.PostEntryCommand(merchantAccount.getId(), Money.of(2500L, "USD"))
                )
        );

        JournalTransaction tx = ledgerService.postTransaction(command);

        assertThat(tx).isNotNull();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(tx.getEntries()).hasSize(2);

        Account reloadedCustomer = ledgerService.getAccount(customerAccount.getId());
        Account reloadedMerchant = ledgerService.getAccount(merchantAccount.getId());

        assertThat(reloadedCustomer.getBalance()).isEqualTo(7500L);
        assertThat(reloadedMerchant.getBalance()).isEqualTo(2500L);
    }

    @Test
    @DisplayName("Rejects unbalanced journal transaction and rolls back")
    void rejectsUnbalancedTransaction() {
        String ref = "TX-UNBALANCED-" + UUID.randomUUID();
        PostTransactionCommand command = new PostTransactionCommand(
                ref,
                TransactionType.PAYMENT_CAPTURE,
                "Unbalanced",
                List.of(
                        new PostTransactionCommand.PostEntryCommand(customerAccount.getId(), Money.of(-1000L, "USD")),
                        new PostTransactionCommand.PostEntryCommand(merchantAccount.getId(), Money.of(500L, "USD")) // sum is -500 != 0
                )
        );

        assertThatThrownBy(() -> ledgerService.postTransaction(command))
                .isInstanceOf(UnbalancedJournalException.class)
                .hasMessageContaining("non-zero sum");

        assertThat(transactionRepository.findByReference(ref)).isEmpty();
    }

    @Test
    @DisplayName("Enforces balance constraint: account without allowNegative cannot overdraft")
    void preventsOverdraft() {
        // Customer has 0 balance and allowNegative = false
        String ref = "TX-OVERDRAFT-" + UUID.randomUUID();
        PostTransactionCommand command = new PostTransactionCommand(
                ref,
                TransactionType.PAYMENT_CAPTURE,
                "Overdraft test",
                List.of(
                        new PostTransactionCommand.PostEntryCommand(customerAccount.getId(), Money.of(-5000L, "USD")),
                        new PostTransactionCommand.PostEntryCommand(merchantAccount.getId(), Money.of(5000L, "USD"))
                )
        );

        assertThatThrownBy(() -> ledgerService.postTransaction(command))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(transactionRepository.findByReference(ref)).isEmpty();
        assertThat(ledgerService.getAccount(customerAccount.getId()).getBalance()).isZero();
    }

    @Test
    @DisplayName("Enforces append-only: database trigger strictly forbids UPDATE or DELETE on journal entries")
    void appendOnlyEnforcedAtDbLevel() {
        Account settlement = ledgerService.createAccount("SETTLE-" + UUID.randomUUID(), AccountType.SETTLEMENT, "USD", true);
        String ref = "TX-IMMUTABLE-" + UUID.randomUUID();
        JournalTransaction tx = ledgerService.postTransaction(new PostTransactionCommand(
                ref,
                TransactionType.SETTLEMENT,
                "Immutability test",
                List.of(
                        new PostTransactionCommand.PostEntryCommand(customerAccount.getId(), Money.of(1000L, "USD")),
                        new PostTransactionCommand.PostEntryCommand(settlement.getId(), Money.of(-1000L, "USD"))
                )
        ));

        UUID entryId = tx.getEntries().getFirst().getId();

        // Attempting an UPDATE on journal_entries must fail via trigger
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE journal_entries SET amount = 9999 WHERE id = ?", entryId))
                .hasMessageContaining("Journal is append-only");

        // Attempting a DELETE on journal_entries must fail via trigger
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM journal_entries WHERE id = ?", entryId))
                .hasMessageContaining("Journal is append-only");
    }

    @Test
    @DisplayName("Reversal transaction creates compensating entries and restores original balances")
    void reversesTransactionSuccessfully() {
        Account settlement = ledgerService.createAccount("SETTLE-" + UUID.randomUUID(), AccountType.SETTLEMENT, "USD", true);
        ledgerService.postTransaction(new PostTransactionCommand(
                "FUND-" + UUID.randomUUID(),
                TransactionType.SETTLEMENT,
                "Funding",
                List.of(
                        new PostTransactionCommand.PostEntryCommand(customerAccount.getId(), Money.of(5000L, "USD")),
                        new PostTransactionCommand.PostEntryCommand(settlement.getId(), Money.of(-5000L, "USD"))
                )
        ));

        String paymentRef = "PAYMENT-" + UUID.randomUUID();
        ledgerService.postTransaction(new PostTransactionCommand(
                paymentRef,
                TransactionType.PAYMENT_CAPTURE,
                "Original Payment",
                List.of(
                        new PostTransactionCommand.PostEntryCommand(customerAccount.getId(), Money.of(-2000L, "USD")),
                        new PostTransactionCommand.PostEntryCommand(merchantAccount.getId(), Money.of(2000L, "USD"))
                )
        ));

        assertThat(ledgerService.getAccount(customerAccount.getId()).getBalance()).isEqualTo(3000L);
        assertThat(ledgerService.getAccount(merchantAccount.getId()).getBalance()).isEqualTo(2000L);

        // Reverse
        String reversalRef = "REVERSAL-" + UUID.randomUUID();
        JournalTransaction reversalTx = ledgerService.reverseTransaction(paymentRef, reversalRef, "Customer dispute");

        assertThat(reversalTx.getTransactionType()).isEqualTo(TransactionType.PAYMENT_REVERSAL);

        // Balances restored
        assertThat(ledgerService.getAccount(customerAccount.getId()).getBalance()).isEqualTo(5000L);
        assertThat(ledgerService.getAccount(merchantAccount.getId()).getBalance()).isZero();

        // Original transaction status is REVERSED
        JournalTransaction originalReloaded = transactionRepository.findByReference(paymentRef).orElseThrow();
        assertThat(originalReloaded.getStatus()).isEqualTo(TransactionStatus.REVERSED);
    }
}
