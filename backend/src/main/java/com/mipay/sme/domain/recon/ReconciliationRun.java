package com.mipay.sme.domain.recon;

import com.mipay.sme.domain.Enums.ReconStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** End-of-day three-ledger comparison (UAT SB p.14, BC p.18). */
@Entity
@Table(name = "reconciliation_runs")
@Getter @Setter
public class ReconciliationRun {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Column(name = "issuer_total", precision = 18, scale = 2)
    private BigDecimal issuerTotal;

    @Column(name = "subledger_total", precision = 18, scale = 2)
    private BigDecimal subledgerTotal;

    @Column(name = "credit_ledger_total", precision = 18, scale = 2)
    private BigDecimal creditLedgerTotal;

    @Column(name = "breaks_count")
    private Integer breaksCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
