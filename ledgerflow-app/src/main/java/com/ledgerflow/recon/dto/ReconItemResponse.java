package com.ledgerflow.recon.dto;

import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;

import java.time.Instant;
import java.util.UUID;

public record ReconItemResponse(
        UUID id,
        UUID settlementFileId,
        MismatchType mismatchType,
        String paymentReference,
        Long bankAmount,
        Long ledgerAmount,
        String currency,
        String status,
        Instant createdAt
) {
    public static ReconItemResponse from(ReconItem item) {
        return new ReconItemResponse(
                item.getId(),
                item.getSettlementFile().getId(),
                item.getMismatchType(),
                item.getPaymentReference(),
                item.getBankAmount(),
                item.getLedgerAmount(),
                item.getCurrency(),
                item.getStatus(),
                item.getCreatedAt()
        );
    }
}
