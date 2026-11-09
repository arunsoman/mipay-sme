package com.mipay.sme.domain.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Availability / latency / TPS samples (X13, UAT SB p.17). */
@Entity
@Table(name = "monitoring_metrics", indexes = @Index(columnList = "metric_name"))
@Getter @Setter
public class MonitoringMetric {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "metric_value", precision = 18, scale = 4)
    private BigDecimal value;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (recordedAt == null) recordedAt = Instant.now();
    }
}
