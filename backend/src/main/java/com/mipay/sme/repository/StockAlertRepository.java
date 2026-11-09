package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockAlertRepository extends JpaRepository<StockAlert, String> {

    List<StockAlert> findByMerchantIdAndResolvedFalse(String merchantId);

    List<StockAlert> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
