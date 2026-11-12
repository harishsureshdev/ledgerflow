package com.ledgerflow.recon.repository;

import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconItemRepository extends JpaRepository<ReconItem, UUID> {

    List<ReconItem> findBySettlementFileId(UUID settlementFileId);

    List<ReconItem> findByMismatchType(MismatchType mismatchType);

    List<ReconItem> findByStatus(String status);
}
