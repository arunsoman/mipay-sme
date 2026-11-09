package com.mipay.sme.repository;

import com.mipay.sme.domain.compliance.AmlAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AmlAlertRepository extends JpaRepository<AmlAlert, String> {

    List<AmlAlert> findAllByOrderByCreatedAtDesc();

    List<AmlAlert> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    List<AmlAlert> findByEntityIdAndStatus(String entityId, com.mipay.sme.domain.Enums.AmlStatus status);
}
