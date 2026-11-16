package com.ledgerflow.payments;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.outbox.OutboxEvent;
import com.ledgerflow.payments.outbox.OutboxRepository;
import com.ledgerflow.payments.outbox.OutboxStatus;
import com.ledgerflow.payments.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Bug: @Scheduled methods never ran because scheduling was never enabled, so in the running
 * app outbox events stayed PENDING forever and nothing reached Kafka. The other outbox test
 * hid this by calling the relay by hand. This test never calls the relay.
 */
@TestPropertySource(properties = "ledgerflow.scheduling.enabled=true")
@DirtiesContext // close this context afterwards so its scheduled relay doesn't keep running during other tests
class OutboxRelaySchedulingIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private OutboxRepository outboxRepository;

    @Test
    @DisplayName("Outbox relay runs on its own schedule: a captured payment's event is published without a manual call")
    void relayRunsOnSchedule() {
        var result = paymentService.processPayment(
                "merch_" + UUID.randomUUID(), "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_" + UUID.randomUUID(), 1234L, "USD", "tok_visa_4242"));
        String paymentId = result.response().id().toString();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<OutboxEvent> events = outboxRepository.findByAggregateId(paymentId);
            assertThat(events).isNotEmpty();
            assertThat(events).allMatch(e -> e.getStatus() == OutboxStatus.PUBLISHED);
        });
    }
}
