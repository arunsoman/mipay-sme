package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KycRuleRepository extends JpaRepository<KycRule, String> {

    List<KycRule> findBySchemaVersionIdAndPhase(String schemaVersionId, KycRule.Phase phase);
}
