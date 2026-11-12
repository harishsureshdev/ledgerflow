package com.ledgerflow.recon.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recon_items")
public class ReconItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_file_id", nullable = false)
    private SettlementFile settlementFile;

    @Enumerated(EnumType.STRING)
    @Column(name = "mismatch_type", nullable = false, length = 32)
    private MismatchType mismatchType;

    @Column(name = "payment_reference", length = 128)
    private String paymentReference;

    @Column(name = "bank_amount")
    private Long bankAmount;

    @Column(name = "ledger_amount")
    private Long ledgerAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ReconItem() {
    }

    public ReconItem(
            SettlementFile settlementFile,
            MismatchType mismatchType,
            String paymentReference,
            Long bankAmount,
            Long ledgerAmount,
            String currency
    ) {
        this.settlementFile = settlementFile;
        this.mismatchType = mismatchType;
        this.paymentReference = paymentReference;
        this.bankAmount = bankAmount;
        this.ledgerAmount = ledgerAmount;
        this.currency = currency;
        this.status = "OPEN";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public SettlementFile getSettlementFile() {
        return settlementFile;
    }

    public MismatchType getMismatchType() {
        return mismatchType;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public Long getBankAmount() {
        return bankAmount;
    }

    public Long getLedgerAmount() {
        return ledgerAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
