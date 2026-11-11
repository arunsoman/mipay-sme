package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.kyc.KycApplication;
import org.springframework.data.jpa.repository.JpaRepository;

/** KYC applications (V7 model, extended V8). */

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KycApplicationRepository extends JpaRepository<KycApplication, String> {

    /** Legacy single-row lookup (pre-V10 rows keep their org_id uniqueness in practice). */
    Optional<KycApplication> findByOrgId(String orgId);

    Optional<KycApplication> findFirstByOrgIdOrderByCreatedAtDesc(String orgId);

    List<KycApplication> findByOrgIdIn(Collection<String> orgIds);

    /** §9: one OPEN application per org (partial unique index on Postgres). */
    Optional<KycApplication> findFirstByOrgIdAndStatusInOrderByCreatedAtDesc(String orgId,
                                                                            Collection<KycApplication.Status> statuses);

    List<KycApplication> findByOrgIdAndStatusIn(String orgId, Collection<KycApplication.Status> statuses);

    List<KycApplication> findByOrgIdInAndStatusIn(Collection<String> orgIds,
                                                  Collection<KycApplication.Status> statuses);

    List<KycApplication> findByStatusInOrderByUpdatedAtDesc(Collection<KycApplication.Status> statuses);

    List<KycApplication> findByStatusOrderByUpdatedAtDesc(KycApplication.Status status);
}
