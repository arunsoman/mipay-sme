package com.mipay.sme.repository;

import com.mipay.sme.domain.compliance.DsarRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DsarRequestRepository extends JpaRepository<DsarRequest, String> {

    List<DsarRequest> findAllByOrderByRequestedAtDesc();
}
