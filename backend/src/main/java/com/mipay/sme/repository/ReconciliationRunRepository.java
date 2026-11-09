package com.mipay.sme.repository;

import com.mipay.sme.domain.recon.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, String> {

    Optional<ReconciliationRun> findTopByOrderByCreatedAtDesc();

    List<ReconciliationRun> findAllByOrderByCreatedAtDesc();
}
