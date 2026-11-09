package com.mipay.sme.domain.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "portfolio_snapshots")
@Getter @Setter
public class PortfolioSnapshot {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "lender_id", length = 36, nullable = false)
    private String lenderId;

    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;

    @Column(name = "outstanding_principal", precision = 18, scale = 2)
    private BigDecimal outstandingPrincipal;

    @Column(name = "arrears_amount", precision = 18, scale = 2)
    private BigDecimal arrearsAmount;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
