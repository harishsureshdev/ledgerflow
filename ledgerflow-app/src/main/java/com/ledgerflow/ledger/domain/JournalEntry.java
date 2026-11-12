package com.ledgerflow.ledger.domain;

import com.ledgerflow.common.domain.Money;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private JournalTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "entry_order", nullable = false, updatable = false)
    private int entryOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected JournalEntry() {
    }

    public JournalEntry(Account account, Money money, int entryOrder) {
        this.account = Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(money, "money must not be null");
        this.amount = money.amount();
        this.currency = money.currency();
        this.entryOrder = entryOrder;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public JournalTransaction getTransaction() {
        return transaction;
    }

    void setTransaction(JournalTransaction transaction) {
        this.transaction = transaction;
    }

    public Account getAccount() {
        return account;
    }

    public Long getAmount() {
        return amount;
    }

    public Money getMoney() {
        return Money.of(amount, currency);
    }

    public String getCurrency() {
        return currency;
    }

    public int getEntryOrder() {
        return entryOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
