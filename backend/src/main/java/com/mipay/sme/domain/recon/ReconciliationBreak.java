package com.mipay.sme.domain.recon;

import com.mipay.sme.domain.Enums.BreakStatus;
import com.mipay.sme.domain.Enums.BreakType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_breaks", indexes = @Index(columnList = "run_id"))
@Getter @Setter
public class ReconciliationBreak {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "run_id", length = 36, nullable = false)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "break_type", nullable = false)
    private BreakType breakType;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    private String reference;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BreakStatus status = BreakStatus.OPEN;

    @Column(name = "resolved_by", length = 36)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
