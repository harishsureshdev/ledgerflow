package com.ledgerflow.ledger.service;

import com.ledgerflow.common.domain.Money;
import com.ledgerflow.ledger.domain.TransactionType;

import java.util.List;
import java.util.UUID;

public record PostTransactionCommand(
        String reference,
        TransactionType transactionType,
        String description,
        List<PostEntryCommand> entries
) {
    public record PostEntryCommand(
            UUID accountId,
            Money money
    ) {}
}
