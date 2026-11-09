package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.BreakStatus;
import com.mipay.sme.domain.recon.ReconciliationBreak;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationBreakRepository extends JpaRepository<ReconciliationBreak, String> {

    List<ReconciliationBreak> findByRunId(String runId);

    List<ReconciliationBreak> findByStatus(BreakStatus status);

    List<ReconciliationBreak> findAllByOrderByCreatedAtDesc();

    /** Open-break dedup: the same unexplained gap must persist once (TC-RACE-11). */
    boolean existsByBreakTypeAndReferenceAndStatus(com.mipay.sme.domain.Enums.BreakType breakType,
                                                   String reference, BreakStatus status);
}
