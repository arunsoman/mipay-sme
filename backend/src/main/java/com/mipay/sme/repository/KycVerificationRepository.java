package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycVerificationRepository extends JpaRepository<KycVerification, String> {

    List<KycVerification> findByApplicationIdAndSubmissionNo(String applicationId, int submissionNo);

    Optional<KycVerification> findByApplicationIdAndSubmissionNoAndCheckTypeAndSubject(
            String applicationId, int submissionNo, String checkType, String subject);
}
