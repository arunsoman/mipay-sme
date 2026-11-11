package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycPlugin;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycPluginRepository extends JpaRepository<KycPlugin, String> {
}
