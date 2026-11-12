package com.ledgerflow.payments;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.domain.JournalTransaction;
import com.ledgerflow.ledger.repository.AccountRepository;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.ledger.repository.JournalTransactionRepository;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.ledger.service.LedgerService;
import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.idempotency.IdempotencyConflictException;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.payments.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JournalTransactionRepository transactionRepository;

    @Autowired
    private JournalEntryRepository entryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountProvisioningService accountProvisioningService;

    @Autowired
    private LedgerService ledgerService;

    @AfterEach
    void verifyInvariant() {
        // Assert journal sums to zero after every money operation
        ledgerService.assertJournalSumsToZero();
    }

    @Test
    @DisplayName("100 parallel requests with the same idempotency key result in exactly ONE payment (zero double charges)")
    void oneHundredParallelRequestsResultInExactlyOnePayment() throws Exception {
        int threadCount = 100;
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();
        long paymentAmount = 2500L; // $25.00

        // Ensure customer has sufficient balance
        Account customerAccount = accountProvisioningService.getOrCreateCustomerAccount(customerId, "USD", 100_000L);
        long initialCustomerBalance = customerAccount.getBalance();

        CreatePaymentRequest request = new CreatePaymentRequest(
                customerId,
                paymentAmount,
                "USD",
                "tok_visa_4242"
        );

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger replayCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicInteger unexpectedErrorCount = new AtomicInteger();
        List<UUID> observedPaymentIds = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // Wait for all threads to be ready for maximum contention
                    PaymentService.PaymentResult result = paymentService.processPayment(merchantId, idempotencyKey, request);
                    observedPaymentIds.add(result.response().id());
                    if (result.replayed()) {
                        replayCount.incrementAndGet();
                    } else {
                        successCount.incrementAndGet();
                    }
                } catch (IdempotencyConflictException e) {
                    // Concurrent request arrived while first was IN_PROGRESS (HTTP 409)
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    unexpectedErrorCount.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Release the latch: all 100 threads fire concurrently
        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedErrorCount.get()).isZero();

        // Exactly one thread executed the payment transaction
        assertThat(successCount.get()).isEqualTo(1);

        // All successful and replayed requests observed the exact same payment ID
        assertThat(observedPaymentIds).isNotEmpty();
        UUID singlePaymentId = observedPaymentIds.getFirst();
        assertThat(observedPaymentIds).allMatch(id -> id.equals(singlePaymentId));

        // 1. Verify in database: exactly ONE payment record exists
        List<Payment> merchantPayments = paymentRepository.findByMerchantId(merchantId);
        assertThat(merchantPayments).hasSize(1);
        Payment payment = merchantPayments.getFirst();
        assertThat(payment.getId()).isEqualTo(singlePaymentId);
        assertThat(payment.getAmount()).isEqualTo(paymentAmount);

        // 2. Verify in database: exactly ONE journal transaction exists for this payment
        String expectedTxRef = "PAYMENT-" + singlePaymentId;
        JournalTransaction journalTx = transactionRepository.findByReferenceWithEntries(expectedTxRef).orElseThrow();
        assertThat(journalTx.getEntries()).hasSize(2);

        // 3. Customer was charged exactly ONCE: initialBalance - paymentAmount
        Account reloadedCustomer = ledgerService.getAccount(customerAccount.getId());
        assertThat(reloadedCustomer.getBalance()).isEqualTo(initialCustomerBalance - paymentAmount);

        // 4. Ledger global balance invariant holds: sum of all journal entries = 0
        ledgerService.assertJournalSumsToZero();
    }
}
