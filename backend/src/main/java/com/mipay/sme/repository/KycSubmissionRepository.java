package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycSubmissionRepository extends JpaRepository<KycSubmission, String> {

    Optional<KycSubmission> findByApplicationIdAndSubmissionNo(String applicationId, int submissionNo);

    @org.springframework.data.jpa.repository.Query("select coalesce(max(s.submissionNo), 0) from KycSubmission s where s.applicationId = :applicationId")
    int findMaxSubmissionNo(@org.springframework.data.repository.query.Param("applicationId") String applicationId);

    List<KycSubmission> findByApplicationIdOrderBySubmissionNoAsc(String applicationId);
}
