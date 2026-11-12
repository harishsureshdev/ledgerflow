package com.ledgerflow.investigator.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record InvestigationProposal(
        UUID id,
        UUID reconItemId,
        String rootCause,
        List<String> evidenceIds,
        String proposedAction,
        Long recommendedAmount,
        String currency,
        String rationale,
        double confidenceScore,
        String status,
        Instant createdAt
) {
    public static InvestigationProposal from(InvestigationProposalEntity entity) {
        List<String> evidence = entity.getEvidenceIds() != null && !entity.getEvidenceIds().isBlank()
                ? Arrays.asList(entity.getEvidenceIds().split(","))
                : Collections.emptyList();

        return new InvestigationProposal(
                entity.getId(),
                entity.getReconItemId(),
                entity.getRootCause(),
                evidence,
                entity.getProposedAction(),
                entity.getRecommendedAmount(),
                entity.getCurrency(),
                entity.getRationale(),
                entity.getConfidenceScore(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }
}
