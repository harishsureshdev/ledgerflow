package com.ledgerflow.payments.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    /**
     * Claims up to 50 pending events for this relay run. FOR UPDATE SKIP LOCKED makes concurrent
     * relays (several app instances) take disjoint rows instead of all publishing the same ones.
     * Must run inside the relay's transaction; the locks are held until it commits.
     */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT * FROM outbox
        WHERE status = 'PENDING'
        ORDER BY created_at
        LIMIT 50
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> claimPendingBatch();

    List<OutboxEvent> findByAggregateId(String aggregateId);

    long countByStatus(OutboxStatus status);
}
