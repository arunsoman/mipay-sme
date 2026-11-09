package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.KhataAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KhataAccountRepository extends JpaRepository<KhataAccount, String> {

    List<KhataAccount> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
