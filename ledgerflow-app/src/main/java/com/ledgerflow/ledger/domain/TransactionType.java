package com.ledgerflow.ledger.domain;

public enum TransactionType {
    PAYMENT_CAPTURE,
    PAYMENT_REVERSAL,
    SETTLEMENT,
    FEE,
    ADJUSTMENT,
    /** Money entering the platform from outside (opening balance / top-up), from the EXTERNAL-FUNDING account. */
    FUNDING
}
