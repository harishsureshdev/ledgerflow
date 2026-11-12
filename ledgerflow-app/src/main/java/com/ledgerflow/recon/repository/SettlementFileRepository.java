package com.ledgerflow.recon.repository;

import com.ledgerflow.recon.domain.SettlementFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementFileRepository extends JpaRepository<SettlementFile, UUID> {

    Optional<SettlementFile> findByFilename(String filename);
}
