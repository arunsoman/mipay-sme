package com.mipay.sme.repository;

import com.mipay.sme.domain.compliance.SarReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SarReportRepository extends JpaRepository<SarReport, String> {

    List<SarReport> findAllByOrderByCreatedAtDesc();
}
