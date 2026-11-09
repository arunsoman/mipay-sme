package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.IncidentStatus;
import com.mipay.sme.domain.ops.Incident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, String> {

    List<Incident> findAllByOrderByCreatedAtDesc();

    List<Incident> findByStatusInOrderByCreatedAtDesc(List<IncidentStatus> statuses);

    List<Incident> findByStatus(IncidentStatus status);
}
