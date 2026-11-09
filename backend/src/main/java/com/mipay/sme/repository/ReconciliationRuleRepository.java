package com.mipay.sme.repository;

import com.mipay.sme.domain.recon.ReconciliationRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRuleRepository extends JpaRepository<ReconciliationRule, String> { }
