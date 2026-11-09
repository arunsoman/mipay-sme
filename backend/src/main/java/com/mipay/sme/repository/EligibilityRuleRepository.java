package com.mipay.sme.repository;

import com.mipay.sme.domain.recon.EligibilityRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EligibilityRuleRepository extends JpaRepository<EligibilityRule, String> {

    Optional<EligibilityRule> findByRuleCode(String ruleCode);
}
