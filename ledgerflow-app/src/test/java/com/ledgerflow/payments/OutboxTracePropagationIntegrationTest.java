package com.ledgerflow.payments;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.outbox.OutboxEvent;
import com.ledgerflow.payments.outbox.OutboxRelay;
import com.ledgerflow.payments.outbox.OutboxRepository;
import com.ledgerflow.payments.service.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payment's trace must survive the transactional outbox: request -> outbox row (DB) ->
 * relay (another thread, later) -> Kafka record -> consumer. Without the stored traceparent the
 * relay's publish would start a brand-new, unrelated trace.
 */
@AutoConfigureObservability
@TestPropertySource(properties = {
        "management.tracing.sampling.probability=1.0",
        "management.otlp.tracing.endpoint=http://127.0.0.1:1/v1/traces" // spans are not exported in tests
})
class OutboxTracePropagationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private Tracer tracer;
    @Autowired private PaymentService paymentService;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private MeterRegistry meters;

    @Test
    @DisplayName("Kafka record published by the outbox relay carries the trace id of the request that created the payment")
    void traceSurvivesTheOutbox() {
        Span request = tracer.nextSpan().name("test-payment-request").start();
        String traceId = request.context().traceId();
        UUID paymentId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            paymentId = paymentService.processPayment("merch_" + UUID.randomUUID(), "key_" + UUID.randomUUID(),
                    new CreatePaymentRequest("cust_" + UUID.randomUUID(), 777L, "USD", "tok_visa_4242")).response().id();
        } finally {
            request.end();
        }

        OutboxEvent event = outboxRepository.findByAggregateId(paymentId.toString()).getFirst();
        assertThat(event.getTraceParent()).as("trace context stored with the outbox row").contains(traceId);

        outboxRelay.relayPendingEvents(); // runs with no active span, like the scheduled relay

        String recordTraceParent = findTraceParentOnKafka(event.getId());
        assertThat(recordTraceParent).as("traceparent header on the Kafka record").contains(traceId);

        assertThat(meters.counter("ledgerflow.payments", "outcome", "captured").count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.find("ledgerflow.outbox.pending").gauge()).isNotNull();
    }

    private String findTraceParentOnKafka(UUID eventId) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "trace-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(OutboxRelay.PAYMENT_EVENTS_TOPIC));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (eventId.toString().equals(header(r, "event_id"))) {
                        return header(r, "traceparent");
                    }
                }
            }
        }
        throw new AssertionError("outbox event " + eventId + " not found on Kafka");
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
