package com.ledgerflow.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLogEntry log(String actor, String role, String action, String resourceType, String resourceId, String details) {
        AuditLogEntry entry = new AuditLogEntry(actor, role, action, resourceType, resourceId, details);
        return auditLogRepository.save(entry);
    }
}
