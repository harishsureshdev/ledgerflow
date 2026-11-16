package com.ledgerflow.recon.service;

/** Thrown when a proposal is no longer PENDING_APPROVAL (e.g. already approved). Maps to 409. */
public class ProposalNotApprovableException extends RuntimeException {
    public ProposalNotApprovableException(String message) {
        super(message);
    }
}
