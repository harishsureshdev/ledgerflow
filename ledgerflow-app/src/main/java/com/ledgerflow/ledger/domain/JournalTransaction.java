package com.ledgerflow.ledger.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "journal_transactions")
public class JournalTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reference", nullable = false, unique = true, length = 128)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 64)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStatus status = TransactionStatus.POSTED;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = false, fetch = FetchType.LAZY)
    @OrderBy("entryOrder ASC")
    private List<JournalEntry> entries = new ArrayList<>();

    protected JournalTransaction() {
    }

    public JournalTransaction(String reference, TransactionType transactionType, String description) {
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.transactionType = Objects.requireNonNull(transactionType, "transactionType must not be null");
        this.description = description;
        this.status = TransactionStatus.POSTED;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<JournalEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public void addEntry(JournalEntry entry) {
        entries.add(entry);
        entry.setTransaction(this);
    }
}
