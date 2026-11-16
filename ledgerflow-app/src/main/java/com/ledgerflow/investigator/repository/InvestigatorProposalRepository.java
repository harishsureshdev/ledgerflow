package com.ledgerflow.investigator.repository;

import com.ledgerflow.investigator.domain.InvestigationProposalEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestigatorProposalRepository extends JpaRepository<InvestigationProposalEntity, UUID> {

    Optional<InvestigationProposalEntity> findByReconItemId(UUID reconItemId);

    /** Row lock so concurrent approvals of the same proposal serialize; only the first sees PENDING_APPROVAL. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM InvestigationProposalEntity p WHERE p.id = :id")
    Optional<InvestigationProposalEntity> findByIdForUpdate(@Param("id") UUID id);

    List<InvestigationProposalEntity> findByStatus(String status);
}
