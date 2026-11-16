package com.ledgerflow.payments;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.outbox.OutboxRelay;
import com.ledgerflow.payments.outbox.OutboxRepository;
import com.ledgerflow.payments.outbox.OutboxStatus;
import com.ledgerflow.payments.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug: the relay read pending rows without locking them, so two app instances (or two relay runs)
 * could both publish the same event. Consumers dedupe, but each event should be published once.
 * With FOR UPDATE SKIP LOCKED each relay claims a disjoint set of rows.
 */
class OutboxRelayConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxRelay outboxRelay;

    @Test
    @DisplayName("Four relays running at the same time publish every pending event exactly once in total")
    void concurrentRelaysDoNotDoublePublish() throws Exception {
        for (int i = 0; i < 40; i++) {
            paymentService.processPayment("merch_" + UUID.randomUUID(), "key_" + UUID.randomUUID(),
                    new CreatePaymentRequest("cust_" + UUID.randomUUID(), 100L + i, "USD", "tok_visa_4242"));
        }
        long pending = outboxRepository.countByStatus(OutboxStatus.PENDING);
        assertThat(pending).isGreaterThanOrEqualTo(40);

        int relays = 4;
        ExecutorService pool = Executors.newFixedThreadPool(relays);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < relays; i++) {
            results.add(pool.submit(() -> {
                start.await();
                int total = 0, n;
                while ((n = outboxRelay.relayPendingEvents()) > 0) total += n; // drain like the scheduler would
                return total;
            }));
        }
        start.countDown();
        int published = 0;
        for (Future<Integer> f : results) published += f.get();
        pool.shutdown();

        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isZero();
        assertThat(published).as("total publishes across relays == events, i.e. no event published twice").isEqualTo((int) pending);
    }
}
