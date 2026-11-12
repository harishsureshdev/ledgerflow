package com.ledgerflow.recon.service;

public record SettlementLine(
        String lineId,
        String paymentReference,
        long amount,
        String currency,
        String status
) {
}
