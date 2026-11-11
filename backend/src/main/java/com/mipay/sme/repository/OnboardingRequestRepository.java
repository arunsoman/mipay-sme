package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.OnboardingKind;
import com.mipay.sme.domain.Enums.OnboardingStatus;
import com.mipay.sme.domain.org.OnboardingRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OnboardingRequestRepository extends JpaRepository<OnboardingRequest, String> {

    /** Pessimistic lock: a second decision must not race the first (decide spec). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OnboardingRequest r where r.id = :id")
    Optional<OnboardingRequest> findByIdForUpdate(@Param("id") String id);

    List<OnboardingRequest> findByStatusOrderByCreatedAtDesc(OnboardingStatus status);

    List<OnboardingRequest> findByOrgIdAndStatus(String orgId, OnboardingStatus status);

    boolean existsByOrgId(String orgId);

    List<OnboardingRequest> findByOrgIdAndKind(String orgId, OnboardingKind kind);
}
