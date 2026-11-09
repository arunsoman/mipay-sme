package com.mipay.sme.repository;

import com.mipay.sme.domain.compliance.KycCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KycCheckRepository extends JpaRepository<KycCheck, String> {

    List<KycCheck> findByEntityTypeAndEntityIdOrderByCheckedAtDesc(String entityType, String entityId);
}
