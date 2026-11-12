package com.ledgerflow.recon.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_files")
public class SettlementFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "filename", nullable = false, unique = true, length = 128)
    private String filename;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PROCESSED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SettlementFile() {
    }

    public SettlementFile(String filename, int totalRecords, long totalAmount, String currency) {
        this.filename = filename;
        this.totalRecords = totalRecords;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.status = "PROCESSED";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public int getTotalRecords() {
        return totalRecords;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
