package com.ledgerflow.processor.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessorEventRepository extends JpaRepository<ProcessorEventRecord, UUID> {

    boolean existsByProcessorEventId(String processorEventId);

    Optional<ProcessorEventRecord> findByProcessorEventId(String processorEventId);

    java.util.List<ProcessorEventRecord> findByPayloadContaining(String text);
}
