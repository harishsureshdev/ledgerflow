package com.ledgerflow.ledger.controller;

import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.domain.JournalTransaction;
import com.ledgerflow.ledger.repository.AccountRepository;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.ledger.repository.JournalTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalTransactionRepository transactionRepository;

    public LedgerController(
            AccountRepository accountRepository,
            JournalEntryRepository journalEntryRepository,
            JournalTransactionRepository transactionRepository
    ) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.transactionRepository = transactionRepository;
    }

    public record AccountSummary(
            UUID id,
            String accountNumber,
            String accountType,
            long balance,
            String currency,
            boolean allowNegative
    ) {
        public static AccountSummary from(Account a) {
            return new AccountSummary(
                    a.getId(),
                    a.getAccountNumber(),
                    a.getAccountType().name(),
                    a.getBalance(),
                    a.getCurrency(),
                    a.isAllowNegative()
            );
        }
    }

    public record BalanceCheckResult(
            boolean balanced,
            long sumAll,
            long totalDebits,
            long totalCredits,
            String message,
            Instant checkedAt
    ) {}

    public record TransactionSummary(
            UUID id,
            String reference,
            String description,
            String transactionType,
            Instant createdAt
    ) {
        public static TransactionSummary from(JournalTransaction t) {
            return new TransactionSummary(
                    t.getId(),
                    t.getReference(),
                    t.getDescription(),
                    t.getTransactionType().name(),
                    t.getCreatedAt()
            );
        }
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<List<AccountSummary>> getAccounts() {
        List<AccountSummary> list = accountRepository.findAll().stream()
                .map(AccountSummary::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/balance-check")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<BalanceCheckResult> checkBalance() {
        long sum = journalEntryRepository.sumAllEntriesAmount();
        long debits = journalEntryRepository.sumAllDebits();
        long credits = journalEntryRepository.sumAllCredits();
        boolean balanced = (sum == 0L);

        return ResponseEntity.ok(new BalanceCheckResult(
                balanced,
                sum,
                debits,
                credits,
                balanced ? "Books balanced ✓" : "Invariant Violation: Journal sum is non-zero!",
                Instant.now()
        ));
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAnyRole('OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<List<TransactionSummary>> getRecentTransactions() {
        List<TransactionSummary> list = transactionRepository.findAll(
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).stream().map(TransactionSummary::from).toList();
        return ResponseEntity.ok(list);
    }
}
