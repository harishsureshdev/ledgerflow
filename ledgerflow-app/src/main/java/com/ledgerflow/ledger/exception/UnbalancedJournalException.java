package com.ledgerflow.ledger.exception;

public class UnbalancedJournalException extends RuntimeException {
    public UnbalancedJournalException(String message) {
        super(message);
    }
}
