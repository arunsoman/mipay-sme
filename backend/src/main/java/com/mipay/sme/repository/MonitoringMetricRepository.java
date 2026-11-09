package com.mipay.sme.repository;

import com.mipay.sme.domain.admin.MonitoringMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonitoringMetricRepository extends JpaRepository<MonitoringMetric, String> {

    Optional<MonitoringMetric> findTopByMetricNameOrderByRecordedAtDesc(String metricName);

    List<MonitoringMetric> findByMetricNameOrderByRecordedAtDesc(String metricName);

    List<MonitoringMetric> findAllByOrderByRecordedAtDesc();
}
