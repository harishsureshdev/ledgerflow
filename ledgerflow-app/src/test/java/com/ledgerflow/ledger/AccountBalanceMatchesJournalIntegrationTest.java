package com.ledgerflow.ledger;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Principle 1 ("money is never created or destroyed") needs more than "journal sums to zero":
 * every account's stored balance must equal the sum of its journal entries. Bug: new customer
 * accounts were given an opening balance written straight into the balance column with no
 * journal entry, i.e. money created from nothing.
 */
class AccountBalanceMatchesJournalIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AccountProvisioningService accountProvisioningService;
    @Autowired private PaymentService paymentService;
    @Autowired private JdbcTemplate jdbc;

    static List<String> accountsWhoseBalanceDisagreesWithJournal(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT a.account_number || ' balance=' || a.balance || ' journal=' || COALESCE(SUM(e.amount), 0)
                FROM accounts a LEFT JOIN journal_entries e ON e.account_id = a.id
                GROUP BY a.id, a.account_number, a.balance
                HAVING a.balance <> COALESCE(SUM(e.amount), 0)
                """, String.class);
    }

    @Test
    @DisplayName("Opening a funded customer account is a balanced journal transaction, not money from nothing")
    void openingBalanceIsJournaled() {
        String customer = "cust_" + UUID.randomUUID();
        Account a = accountProvisioningService.getOrCreateCustomerAccount(customer, "USD", 5_000L);
        assertThat(a.getBalance()).isEqualTo(5_000L);

        // Opening again must not fund twice
        assertThat(accountProvisioningService.getOrCreateCustomerAccount(customer, "USD", 5_000L).getBalance()).isEqualTo(5_000L);

        assertThat(accountsWhoseBalanceDisagreesWithJournal(jdbc)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM journal_entries", Long.class)).isZero();
    }

    @Test
    @DisplayName("After a payment that auto-opens the customer's account, every balance still matches the journal")
    void paymentPathKeepsBalancesConsistent() {
        paymentService.processPayment("merch_" + UUID.randomUUID(), "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_" + UUID.randomUUID(), 1_234L, "USD", "tok_visa_4242"));

        assertThat(accountsWhoseBalanceDisagreesWithJournal(jdbc)).isEmpty();
    }
}
