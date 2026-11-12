package com.ledgerflow.processor.webhook;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processor_events")
public class ProcessorEventRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "processor_event_id", nullable = false, unique = true, length = 128)
    private String processorEventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "signature", nullable = false, length = 256)
    private String signature;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PROCESSED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ProcessorEventRecord() {
    }

    public ProcessorEventRecord(String processorEventId, String eventType, String payload, String signature) {
        this.processorEventId = processorEventId;
        this.eventType = eventType;
        this.payload = payload;
        this.signature = signature;
        this.status = "PROCESSED";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getProcessorEventId() {
        return processorEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getSignature() {
        return signature;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
