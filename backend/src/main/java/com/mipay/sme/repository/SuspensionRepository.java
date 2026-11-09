package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.SuspensionEntityType;
import com.mipay.sme.domain.ops.Suspension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuspensionRepository extends JpaRepository<Suspension, String> {

    List<Suspension> findAllByOrderBySuspendedAtDesc();

    List<Suspension> findByEntityTypeAndEntityIdOrderBySuspendedAtDesc(SuspensionEntityType entityType, String entityId);

    List<Suspension> findByLiftedAtIsNull();

    List<Suspension> findByEntityTypeAndEntityIdAndLiftedAtIsNull(SuspensionEntityType entityType, String entityId);
}
