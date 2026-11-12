package com.ledgerflow.payments.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends an event to the outbox table within the caller's active database transaction.
     * Guarantees atomic dual-write safety: state change + outbox event commit or roll back together.
     */
    @Transactional
    public OutboxEvent recordEvent(String aggregateType, String aggregateId, String eventType, Object payload) {
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox event payload", e);
        }

        OutboxEvent event = new OutboxEvent(aggregateType, aggregateId, eventType, jsonPayload);
        return outboxRepository.save(event);
    }
}
