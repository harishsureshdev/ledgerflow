package com.ledgerflow.ledger.repository;

import com.ledgerflow.ledger.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findByTransactionIdOrderByEntryOrderAsc(UUID transactionId);

    @Query("SELECT e FROM JournalEntry e WHERE e.account.id = :accountId ORDER BY e.createdAt ASC")
    List<JournalEntry> findByAccountId(@Param("accountId") UUID accountId);

    /**
     * Invariant check: Across all journal entries in the ledger, the sum of amounts
     * MUST equal zero (money is never created or destroyed).
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM JournalEntry e")
    long sumAllEntriesAmount();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM JournalEntry e WHERE e.amount > 0")
    long sumAllDebits();

    @Query("SELECT COALESCE(SUM(ABS(e.amount)), 0) FROM JournalEntry e WHERE e.amount < 0")
    long sumAllCredits();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM JournalEntry e WHERE e.transaction.id = :transactionId")
    long sumEntriesByTransactionId(@Param("transactionId") UUID transactionId);
}
