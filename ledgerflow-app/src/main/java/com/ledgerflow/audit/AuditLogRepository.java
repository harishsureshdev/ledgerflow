package com.ledgerflow.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    List<AuditLogEntry> findByResourceTypeAndResourceIdOrderByTimestampDesc(String resourceType, String resourceId);

    List<AuditLogEntry> findByActorOrderByTimestampDesc(String actor);
}
