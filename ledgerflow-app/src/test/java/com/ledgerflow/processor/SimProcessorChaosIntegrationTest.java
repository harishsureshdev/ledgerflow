package com.ledgerflow.processor;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.ledger.service.LedgerService;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.service.PaymentService;
import com.ledgerflow.processor.client.ProcessorChargeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimProcessorChaosIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountProvisioningService accountProvisioningService;

    @Autowired
    private LedgerService ledgerService;

    @Test
    @DisplayName("Chaos: Processor failures and drops leave customer balance untouched and ledger balanced")
    void processorFailureLeavesLedgerBalanced() {
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();

        Account customer = accountProvisioningService.getOrCreateCustomerAccount(customerId, "USD", 50000L);
        long originalBalance = customer.getBalance();

        // Simulate Chaos: processor drops connection / returns FAILED
        testProcessorClient.setHandler(cmd -> new ProcessorChargeResult(
                null, "FAILED", cmd.amount(), cmd.currency(), "Simulated gateway connection drop", Instant.now()
        ));

        CreatePaymentRequest request = new CreatePaymentRequest(customerId, 2000L, "USD", "tok_visa_4242");

        assertThatThrownBy(() -> paymentService.processPayment(merchantId, idempotencyKey, request))
                .isInstanceOf(PaymentService.PaymentDeclinedException.class);

        // Core Invariant Check: customer was not charged, ledger is balanced
        Account reloaded = ledgerService.getAccount(customer.getId());
        assertThat(reloaded.getBalance()).isEqualTo(originalBalance);
        ledgerService.assertJournalSumsToZero();
    }

    @Test
    @DisplayName("Resilience: Processor recovers on retry, payment captures and ledger balances")
    void processorRecoversOnRetry() {
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();

        Account customer = accountProvisioningService.getOrCreateCustomerAccount(customerId, "USD", 50000L);
        long originalBalance = customer.getBalance();

        // Simulate transient network jitter: success on retry
        testProcessorClient.setHandler(cmd -> new ProcessorChargeResult(
                "ch_recovered_" + UUID.randomUUID(), "APPROVED", cmd.amount(), cmd.currency(), null, Instant.now()
        ));

        CreatePaymentRequest request = new CreatePaymentRequest(customerId, 1500L, "USD", "tok_visa_4242");
        PaymentService.PaymentResult result = paymentService.processPayment(merchantId, idempotencyKey, request);

        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.response().amount()).isEqualTo(1500L);

        // Verify balance deducted by exactly 1500 and ledger balances
        Account reloaded = ledgerService.getAccount(customer.getId());
        assertThat(reloaded.getBalance()).isEqualTo(originalBalance - 1500L);
        ledgerService.assertJournalSumsToZero();
    }
}
