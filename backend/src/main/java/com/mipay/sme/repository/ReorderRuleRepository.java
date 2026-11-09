package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.ReorderRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReorderRuleRepository extends JpaRepository<ReorderRule, String> {

    Optional<ReorderRule> findByItemIdAndMerchantId(String itemId, String merchantId);

    List<ReorderRule> findByMerchantId(String merchantId);
}
