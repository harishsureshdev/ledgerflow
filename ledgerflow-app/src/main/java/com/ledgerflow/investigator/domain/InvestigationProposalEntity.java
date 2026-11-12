package com.ledgerflow.investigator.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "investigator_proposals")
public class InvestigationProposalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "recon_item_id", nullable = false)
    private UUID reconItemId;

    @Column(name = "root_cause", nullable = false, length = 64)
    private String rootCause;

    @Column(name = "evidence_ids", nullable = false, columnDefinition = "TEXT")
    private String evidenceIds;

    @Column(name = "proposed_action", nullable = false, length = 64)
    private String proposedAction;

    @Column(name = "recommended_amount")
    private Long recommendedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "rationale", nullable = false, columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING_APPROVAL";

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore = 0.95;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected InvestigationProposalEntity() {
    }

    public InvestigationProposalEntity(
            UUID reconItemId,
            String rootCause,
            String evidenceIds,
            String proposedAction,
            Long recommendedAmount,
            String currency,
            String rationale,
            double confidenceScore
    ) {
        this.reconItemId = reconItemId;
        this.rootCause = rootCause;
        this.evidenceIds = evidenceIds;
        this.proposedAction = proposedAction;
        this.recommendedAmount = recommendedAmount;
        this.currency = currency;
        this.rationale = rationale;
        this.confidenceScore = confidenceScore;
        this.status = "PENDING_APPROVAL";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReconItemId() {
        return reconItemId;
    }

    public String getRootCause() {
        return rootCause;
    }

    public String getEvidenceIds() {
        return evidenceIds;
    }

    public String getProposedAction() {
        return proposedAction;
    }

    public Long getRecommendedAmount() {
        return recommendedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getRationale() {
        return rationale;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
