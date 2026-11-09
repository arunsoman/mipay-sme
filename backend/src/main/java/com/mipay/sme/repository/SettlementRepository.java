package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, String> { }
