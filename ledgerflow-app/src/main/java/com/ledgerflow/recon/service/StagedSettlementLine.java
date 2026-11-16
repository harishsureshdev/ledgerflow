package com.ledgerflow.recon.service;

/** A settlement line plus its 1-based position in the file (the staging key). */
public record StagedSettlementLine(int lineNo, SettlementLine line) {
}
