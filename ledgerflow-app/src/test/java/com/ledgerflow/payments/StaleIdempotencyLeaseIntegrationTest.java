package com.ledgerflow.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.idempotency.IdempotencyConflictException;
import com.ledgerflow.payments.idempotency.IdempotencyKeyRepository;
import com.ledgerflow.payments.idempotency.RequestHasher;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.payments.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bug: if the app crashed mid-payment, the idempotency key stayed IN_PROGRESS forever and every
 * retry got 409. Claims are now leases: after the lease expires a retry takes over, and if the
 * crashed attempt had already committed the payment, the retry returns that payment instead of
 * creating a second one.
 */
class StaleIdempotencyLeaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @AfterEach
    void journalBalances() {
        assertThat(journalEntryRepository.sumAllEntriesAmount()).isZero();
    }

    private CreatePaymentRequest request(String customer) {
        return new CreatePaymentRequest(customer, 3_100L, "USD", "tok_visa_4242");
    }

    /** Leaves an IN_PROGRESS claim behind, as if the process died right after claiming the key. */
    private void abandonedClaim(String merchant, String key, CreatePaymentRequest req, int minutesAgo) throws Exception {
        String hash = RequestHasher.computeHash(objectMapper.writeValueAsString(req));
        tx.executeWithoutResult(s -> idempotencyKeyRepository.insertIfNotExists(merchant, key, hash));
        jdbc.update("UPDATE idempotency_keys SET locked_at = now() - make_interval(mins => ?) WHERE merchant_id = ? AND idempotency_key = ?",
                minutesAgo, merchant, key);
    }

    @Test
    @DisplayName("Crash before the payment was saved: after the lease expires, a retry processes it (not 409 forever)")
    void staleClaimIsTakenOver() throws Exception {
        String merchant = "merch_" + UUID.randomUUID(), customer = "cust_" + UUID.randomUUID(), key = "key_" + UUID.randomUUID();
        abandonedClaim(merchant, key, request(customer), 10);

        var result = paymentService.processPayment(merchant, key, request(customer));

        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customer)).hasSize(1);
    }

    @Test
    @DisplayName("Crash after the payment was saved but before the response was stored: retry returns the same payment, no second charge")
    void takeoverFindsAlreadyCommittedPayment() {
        String merchant = "merch_" + UUID.randomUUID(), customer = "cust_" + UUID.randomUUID(), key = "key_" + UUID.randomUUID();
        UUID firstId = paymentService.processPayment(merchant, key, request(customer)).response().id();
        long entriesAfterFirst = journalEntryRepository.count();

        // Simulate dying between the payment commit and markCompleted
        jdbc.update("""
                UPDATE idempotency_keys SET status = 'IN_PROGRESS', response_body = NULL, status_code = NULL,
                       locked_at = now() - interval '10 minutes'
                WHERE merchant_id = ? AND idempotency_key = ?""", merchant, key);

        var retry = paymentService.processPayment(merchant, key, request(customer));

        assertThat(retry.response().id()).isEqualTo(firstId);
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customer)).hasSize(1);
        assertThat(journalEntryRepository.count()).isEqualTo(entriesAfterFirst);
    }

    @Test
    @DisplayName("A claim that is still fresh (request genuinely in flight) still gets 409")
    void freshClaimStillConflicts() throws Exception {
        String merchant = "merch_" + UUID.randomUUID(), customer = "cust_" + UUID.randomUUID(), key = "key_" + UUID.randomUUID();
        abandonedClaim(merchant, key, request(customer), 0);

        assertThatThrownBy(() -> paymentService.processPayment(merchant, key, request(customer)))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
