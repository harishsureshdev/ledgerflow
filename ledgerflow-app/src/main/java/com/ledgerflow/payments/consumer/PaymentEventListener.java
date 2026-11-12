package com.ledgerflow.payments.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    public static final String CONSUMER_NAME = "ledgerflow-payment-auditor";

    private final ProcessedEventRepository processedEventRepository;
    private final AtomicInteger processedEventCount = new AtomicInteger();
    private final AtomicInteger duplicateSkippedCount = new AtomicInteger();

    public PaymentEventListener(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Idempotent Kafka consumer:
     * Guarantees exactly-once effect over at-least-once Kafka delivery
     * by deduplicating against the processed_events table.
     */
    @KafkaListener(topics = "payment-events", groupId = "ledgerflow-payment-auditor")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("Kafka message missing event_id header. Skipping: {}", record.key());
            return;
        }

        if (processedEventRepository.existsByConsumerNameAndEventId(CONSUMER_NAME, eventId)) {
            log.info("Deduplication: event_id={} already processed by {}. Skipping duplicate.", eventId, CONSUMER_NAME);
            duplicateSkippedCount.incrementAndGet();
            return;
        }

        // Process message business logic
        log.info("Processing event_id={} for aggregate key={}: {}", eventId, record.key(), record.value());

        // Mark as processed atomically
        ProcessedEvent processedEvent = new ProcessedEvent(CONSUMER_NAME, eventId);
        processedEventRepository.save(processedEvent);
        processedEventCount.incrementAndGet();
    }

    private UUID extractEventId(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("event_id");
        if (header != null && header.value() != null) {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        }
        return null;
    }

    public int getProcessedEventCount() {
        return processedEventCount.get();
    }

    public int getDuplicateSkippedCount() {
        return duplicateSkippedCount.get();
    }
}
