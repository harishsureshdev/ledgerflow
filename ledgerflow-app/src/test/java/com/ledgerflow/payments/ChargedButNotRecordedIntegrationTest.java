package com.ledgerflow.payments;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.common.domain.Money;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.domain.TransactionType;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.ledger.service.LedgerService;
import com.ledgerflow.ledger.service.PostTransactionCommand;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.payments.service.PaymentRecordingDeferredException;
import com.ledgerflow.payments.service.PaymentService;
import com.ledgerflow.processor.client.ProcessorChargeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The card processor approved (the card is charged) but LedgerFlow could not record the payment.
 * Bug: the request failed with 500 and the customer stayed charged with no record in the ledger.
 *
 * <ul>
 *   <li>Temporary DB failure: 503, key released; a retry with the same key records it, charged once.</li>
 *   <li>Permanent failure (e.g. funds gone by the time we post): the charge is refunded.</li>
 *   <li>Insufficient funds known up front: declined before the card is ever charged.</li>
 * </ul>
 */
class ChargedButNotRecordedIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AccountProvisioningService accountProvisioningService;
    @Autowired private LedgerService ledgerService;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_payment_insert ON payments");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_payment_insert_once()");
        jdbc.execute("DROP SEQUENCE IF EXISTS fail_payment_insert_seq");
        assertThat(journalEntryRepository.sumAllEntriesAmount()).isZero();
    }

    private CreatePaymentRequest req(String customer, long amount) {
        return new CreatePaymentRequest(customer, amount, "USD", "tok_visa_4242");
    }

    @Test
    @DisplayName("Insufficient funds known before charging: declined (402) and the card is never charged")
    void insufficientFundsIsCheckedBeforeCharging() {
        String customer = "cust_" + UUID.randomUUID();
        accountProvisioningService.getOrCreateCustomerAccount(customer, "USD", 100L);

        assertThatThrownBy(() -> paymentService.processPayment("merch_" + UUID.randomUUID(), "key_" + UUID.randomUUID(), req(customer, 5_000L)))
                .isInstanceOf(PaymentService.PaymentDeclinedException.class);

        assertThat(testProcessorClient.chargesMade()).isZero();
    }

    @Test
    @DisplayName("Funds disappear between charging and recording (permanent failure): the charge is refunded")
    void permanentRecordingFailureRefundsTheCharge() {
        String customer = "cust_" + UUID.randomUUID();
        Account account = accountProvisioningService.getOrCreateCustomerAccount(customer, "USD", 10_000L);
        Account funding = accountProvisioningService.getOrCreateExternalFundingAccount("USD");

        // While the processor is charging, a concurrent spend drains the customer's account
        testProcessorClient.setHandler(cmd -> {
            ledgerService.postTransaction(new PostTransactionCommand("DRAIN-" + UUID.randomUUID(), TransactionType.ADJUSTMENT,
                    "concurrent spend", List.of(
                    new PostTransactionCommand.PostEntryCommand(account.getId(), new Money(-10_000L, "USD")),
                    new PostTransactionCommand.PostEntryCommand(funding.getId(), new Money(10_000L, "USD")))));
            return new ProcessorChargeResult("ch_drained_" + UUID.randomUUID(), "APPROVED", cmd.amount(), cmd.currency(), null, Instant.now());
        });

        String merchant = "merch_" + UUID.randomUUID(), key = "key_" + UUID.randomUUID();
        assertThatThrownBy(() -> paymentService.processPayment(merchant, key, req(customer, 6_000L)))
                .isInstanceOf(PaymentService.PaymentDeclinedException.class);

        assertThat(testProcessorClient.chargesMade()).isEqualTo(1);
        assertThat(testProcessorClient.refunds()).as("the charge that could not be recorded was refunded").hasSize(1);
        assertThat(testProcessorClient.refunds().getFirst().amount()).isEqualTo(6_000L);
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customer)).isEmpty();
    }

    @Test
    @DisplayName("Temporary DB failure after charging: 503, then a retry with the same key records it, charged once")
    void transientRecordingFailureIsRetryable() {
        // Make the next payment INSERT fail with a serialization failure (SQLSTATE 40001), a transient error
        // A sequence is not transactional, so "fire only once" survives the failed transaction's rollback
        jdbc.execute("DROP SEQUENCE IF EXISTS fail_payment_insert_seq");
        jdbc.execute("CREATE SEQUENCE fail_payment_insert_seq");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_payment_insert_once() RETURNS trigger AS $$
                BEGIN
                  IF nextval('fail_payment_insert_seq') = 1 THEN
                    RAISE EXCEPTION 'simulated serialization failure' USING ERRCODE = '40001';
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER trg_fail_payment_insert BEFORE INSERT ON payments FOR EACH ROW EXECUTE FUNCTION fail_payment_insert_once()");

        String customer = "cust_" + UUID.randomUUID(), merchant = "merch_" + UUID.randomUUID(), key = "key_" + UUID.randomUUID();
        accountProvisioningService.getOrCreateCustomerAccount(customer, "USD", 50_000L);

        assertThatThrownBy(() -> paymentService.processPayment(merchant, key, req(customer, 2_000L)))
                .isInstanceOf(PaymentRecordingDeferredException.class);
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customer)).isEmpty();

        var retry = paymentService.processPayment(merchant, key, req(customer, 2_000L));

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customer)).hasSize(1);
        assertThat(testProcessorClient.chargesMade()).as("the processor replayed the original charge").isEqualTo(1);
        assertThat(testProcessorClient.refunds()).isEmpty();
    }
}
