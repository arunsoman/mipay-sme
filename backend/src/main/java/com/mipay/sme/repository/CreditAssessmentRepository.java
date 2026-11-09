package com.mipay.sme.repository;

import com.mipay.sme.domain.credit.CreditAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditAssessmentRepository extends JpaRepository<CreditAssessment, String> {

    Optional<CreditAssessment> findTopByMerchantIdAndLenderIdOrderByCreatedAtDesc(String merchantId, String lenderId);

    List<CreditAssessment> findByLenderIdOrderByCreatedAtDesc(String lenderId);
}
