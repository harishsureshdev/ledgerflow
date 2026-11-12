package com.ledgerflow.ledger.service;

import com.ledgerflow.common.domain.Money;
import com.ledgerflow.ledger.domain.*;
import com.ledgerflow.ledger.exception.AccountNotFoundException;
import com.ledgerflow.ledger.exception.InsufficientFundsException;
import com.ledgerflow.ledger.exception.UnbalancedJournalException;
import com.ledgerflow.ledger.repository.AccountRepository;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.ledger.repository.JournalTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final JournalTransactionRepository transactionRepository;
    private final JournalEntryRepository entryRepository;

    public LedgerService(
            AccountRepository accountRepository,
            JournalTransactionRepository transactionRepository,
            JournalEntryRepository entryRepository
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional
    public Account createAccount(String accountNumber, AccountType accountType, String currency, boolean allowNegative) {
        Account account = new Account(accountNumber, accountType, currency, allowNegative);
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public Account getAccountByNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    /**
     * Posts a double-entry transaction.
     * Core Invariant: sum(entries.amount) == 0.
     *
     * Concurrency & Deadlock Prevention:
     * Account IDs are strictly sorted before acquiring pessimistic write locks (SELECT ... FOR UPDATE).
     * This establishes a global total ordering on row locks across all concurrent threads,
     * preventing database deadlocks.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JournalTransaction postTransaction(PostTransactionCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        List<PostTransactionCommand.PostEntryCommand> entries = command.entries();

        if (entries == null || entries.size() < 2) {
            throw new UnbalancedJournalException("A journal transaction requires at least two balanced entries.");
        }

        if (transactionRepository.existsByReference(command.reference())) {
            throw new IllegalStateException("Transaction reference already exists: " + command.reference());
        }

        // Validate currencies match
        String firstCurrency = entries.getFirst().money().currency();
        boolean currencyMismatch = entries.stream()
                .anyMatch(e -> !e.money().currency().equalsIgnoreCase(firstCurrency));
        if (currencyMismatch) {
            throw new IllegalArgumentException("Multi-currency single transactions are not supported. All entries must share currency " + firstCurrency);
        }

        // Validate journal balance: sum of amounts must equal zero
        long sum = entries.stream().mapToLong(e -> e.money().amount()).sum();
        if (sum != 0) {
            throw new UnbalancedJournalException(
                    String.format("Unbalanced journal entries: transaction '%s' has non-zero sum (%d minor units)",
                            command.reference(), sum));
        }

        // Sort account IDs deterministically to prevent deadlocks when locking rows
        List<UUID> sortedAccountIds = entries.stream()
                .map(PostTransactionCommand.PostEntryCommand::accountId)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        // Lock accounts in order
        List<Account> lockedAccounts = accountRepository.findAllByIdInForUpdate(sortedAccountIds);
        Map<UUID, Account> accountMap = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        if (accountMap.size() != sortedAccountIds.size()) {
            throw new AccountNotFoundException("One or more accounts could not be locked or found");
        }

        JournalTransaction transaction = new JournalTransaction(
                command.reference(),
                command.transactionType(),
                command.description()
        );

        int order = 0;
        for (PostTransactionCommand.PostEntryCommand entryCmd : entries) {
            Account account = accountMap.get(entryCmd.accountId());
            if (!account.getCurrency().equalsIgnoreCase(entryCmd.money().currency())) {
                throw new IllegalArgumentException(String.format(
                        "Account %s currency %s does not match entry currency %s",
                        account.getAccountNumber(), account.getCurrency(), entryCmd.money().currency()));
            }

            try {
                account.applyAmount(entryCmd.money().amount());
            } catch (IllegalStateException e) {
                throw new InsufficientFundsException(e.getMessage());
            }

            JournalEntry entry = new JournalEntry(account, entryCmd.money(), order++);
            transaction.addEntry(entry);
        }

        accountRepository.saveAll(lockedAccounts);
        return transactionRepository.save(transaction);
    }

    /**
     * Reversal entry pattern:
     * Never UPDATE or DELETE past journal entries.
     * Reversal creates a mirror transaction with negated entry amounts.
     */
    @Transactional
    public JournalTransaction reverseTransaction(String originalReference, String reversalReference, String reason) {
        JournalTransaction original = transactionRepository.findByReferenceWithEntries(originalReference)
                .orElseThrow(() -> new IllegalArgumentException("Original transaction not found: " + originalReference));

        if (original.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("Transaction is already reversed: " + originalReference);
        }

        List<PostTransactionCommand.PostEntryCommand> reversalEntries = original.getEntries().stream()
                .map(e -> new PostTransactionCommand.PostEntryCommand(
                        e.getAccount().getId(),
                        e.getMoney().negate()
                ))
                .toList();

        PostTransactionCommand reversalCommand = new PostTransactionCommand(
                reversalReference,
                TransactionType.PAYMENT_REVERSAL,
                "Reversal of " + originalReference + ": " + reason,
                reversalEntries
        );

        JournalTransaction reversalTx = postTransaction(reversalCommand);
        original.setStatus(TransactionStatus.REVERSED);
        transactionRepository.save(original);

        return reversalTx;
    }

    /**
     * Invariant check: Assert that the entire journal sums to zero across the database.
     */
    @Transactional(readOnly = true)
    public void assertJournalSumsToZero() {
        long totalSum = entryRepository.sumAllEntriesAmount();
        if (totalSum != 0) {
            throw new IllegalStateException("CRITICAL INVARIANT VIOLATION: Global journal sum is " + totalSum + " (expected 0)");
        }
    }
}
