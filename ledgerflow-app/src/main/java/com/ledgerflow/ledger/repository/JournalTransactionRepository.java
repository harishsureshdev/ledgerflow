package com.ledgerflow.ledger.repository;

import com.ledgerflow.ledger.domain.JournalTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, UUID> {

    Optional<JournalTransaction> findByReference(String reference);

    boolean existsByReference(String reference);

    @Query("SELECT t FROM JournalTransaction t LEFT JOIN FETCH t.entries WHERE t.reference = :reference")
    Optional<JournalTransaction> findByReferenceWithEntries(@Param("reference") String reference);
}
