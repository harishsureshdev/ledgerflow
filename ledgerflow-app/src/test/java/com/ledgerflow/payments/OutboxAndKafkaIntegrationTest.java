package com.ledgerflow.payments;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.payments.consumer.PaymentEventListener;
import com.ledgerflow.payments.consumer.ProcessedEventRepository;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.dto.PaymentResponse;
import com.ledgerflow.payments.outbox.OutboxEvent;
import com.ledgerflow.payments.outbox.OutboxRelay;
import com.ledgerflow.payments.outbox.OutboxRepository;
import com.ledgerflow.payments.outbox.OutboxStatus;
import com.ledgerflow.payments.service.PaymentService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OutboxAndKafkaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private PaymentEventListener paymentEventListener;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Transactional Outbox: payment writes outbox row atomically, relay publishes to Kafka, consumer dedupes duplicates")
    void outboxRelayAndIdempotentConsumer() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();

        CreatePaymentRequest request = new CreatePaymentRequest(
                customerId,
                5000L,
                "USD",
                "tok_visa_4242"
        );

        // 1. Process payment
        PaymentService.PaymentResult result = paymentService.processPayment(merchantId, idempotencyKey, request);
        PaymentResponse response = result.response();
        UUID paymentId = response.id();

        // 2. Verify outbox row exists in DB in PENDING state (atomic dual-write)
        List<OutboxEvent> outboxEvents = outboxRepository.findByAggregateId(paymentId.toString());
        assertThat(outboxEvents).hasSize(1);
        OutboxEvent outboxEvent = outboxEvents.getFirst();
        UUID eventId = outboxEvent.getId();
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEvent.getEventType()).isEqualTo("PAYMENT_CAPTURED");

        // 3. Relay publishes outbox event to Kafka
        int published = outboxRelay.relayPendingEvents();
        assertThat(published).isGreaterThanOrEqualTo(1);

        OutboxEvent reloadedOutbox = outboxRepository.findById(eventId).orElseThrow();
        assertThat(reloadedOutbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(reloadedOutbox.getProcessedAt()).isNotNull();

        // 4. Verify Kafka consumer processes the event and writes to processed_events table
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            boolean processed = processedEventRepository.existsByConsumerNameAndEventId(
                    PaymentEventListener.CONSUMER_NAME, eventId);
            assertThat(processed).isTrue();
        });

        int initialProcessedCount = paymentEventListener.getProcessedEventCount();
        int initialDuplicateCount = paymentEventListener.getDuplicateSkippedCount();

        // 5. Simulate duplicate Kafka delivery (at-least-once redelivery of same event_id)
        ProducerRecord<String, String> duplicateRecord = new ProducerRecord<>(
                OutboxRelay.PAYMENT_EVENTS_TOPIC,
                paymentId.toString(),
                outboxEvent.getPayload()
        );
        duplicateRecord.headers().add(new RecordHeader("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        duplicateRecord.headers().add(new RecordHeader("event_type", "PAYMENT_CAPTURED".getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(duplicateRecord).get();

        // 6. Verify idempotent consumer ignores duplicate
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(paymentEventListener.getDuplicateSkippedCount()).isGreaterThan(initialDuplicateCount);
            assertThat(paymentEventListener.getProcessedEventCount()).isEqualTo(initialProcessedCount);
        });
    }
}
