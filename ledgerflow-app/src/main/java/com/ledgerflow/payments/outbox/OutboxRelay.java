package com.ledgerflow.payments.outbox;

import com.ledgerflow.common.tracing.TraceContextCarrier;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    public static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private final TraceContextCarrier traceContext;

    public OutboxRelay(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                       TraceContextCarrier traceContext, MeterRegistry meters) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.traceContext = traceContext;
        // How far the relay is behind; alert if this keeps growing (Kafka down, relay stuck)
        Gauge.builder("ledgerflow.outbox.pending", outboxRepository, r -> r.countByStatus(OutboxStatus.PENDING))
                .description("Outbox events not yet published to Kafka")
                .register(meters);
    }

    /**
     * Polls pending outbox records and publishes them to Kafka.
     * At-least-once delivery guarantee: consumers deduplicate by event_id.
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public int relayPendingEvents() {
        List<OutboxEvent> pending = outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pending.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEvent event : pending) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        PAYMENT_EVENTS_TOPIC,
                        event.getAggregateId(),
                        event.getPayload()
                );
                record.headers().add(new RecordHeader("event_id", event.getId().toString().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("event_type", event.getEventType().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("aggregate_type", event.getAggregateType().getBytes(StandardCharsets.UTF_8)));

                // Publish inside a span continuing the trace of the request that created the event;
                // KafkaTemplate's observation then puts that trace's traceparent on the record.
                traceContext.continueTrace(event.getTraceParent(), "outbox.publish", () -> {
                    try {
                        return kafkaTemplate.send(record).get(3, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("Kafka publish failed: " + e.getMessage(), e);
                    }
                });
                event.markPublished();
                publishedCount++;
                log.debug("Outbox relay published event: id={}, type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.error("Failed to relay outbox event id={}: {}", event.getId(), e.getMessage());
                // Leave as PENDING to retry on next cycle
                break;
            }
        }
        outboxRepository.saveAll(pending);
        return publishedCount;
    }
}
