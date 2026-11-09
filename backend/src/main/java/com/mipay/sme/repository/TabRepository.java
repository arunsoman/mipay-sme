package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.Tab;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TabRepository extends JpaRepository<Tab, String> {

    List<Tab> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
