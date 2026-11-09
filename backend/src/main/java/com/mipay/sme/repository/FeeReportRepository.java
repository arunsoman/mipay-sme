package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.FeeReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeeReportRepository extends JpaRepository<FeeReport, String> { }
