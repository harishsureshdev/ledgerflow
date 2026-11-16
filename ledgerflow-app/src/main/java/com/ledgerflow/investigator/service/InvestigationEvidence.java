package com.ledgerflow.investigator.service;

import com.ledgerflow.recon.domain.MismatchType;

import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of everything known about a reconciliation mismatch, gathered with
 * read-only tools inside a short read-only transaction. The AI call happens afterwards,
 * outside any DB transaction.
 */
public record InvestigationEvidence(
        UUID reconItemId,
        UUID settlementFileId,
        MismatchType mismatchType,
        String paymentReference,
        Long bankAmount,
        Long ledgerAmount,
        String currency,
        String paymentStatus,
        int ledgerEntryCount,
        int processorEventCount,
        List<String> evidenceIds
) {
    /** bank - ledger, in minor units (0 when either side is missing). */
    public long delta() {
        if (bankAmount == null || ledgerAmount == null) return 0L;
        return bankAmount - ledgerAmount;
    }
}
