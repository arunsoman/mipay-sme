package com.mipay.sme.domain.ops;

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

/** Fees billed vs collected, per code and date (T13, BC p.18). */
@Entity
@Table(name = "fee_reports")
@Getter @Setter
public class FeeReport {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "fee_code", nullable = false)
    private String feeCode;

    @Column(name = "amount_billed", precision = 18, scale = 2)
    private BigDecimal amountBilled;

    @Column(name = "amount_collected", precision = 18, scale = 2)
    private BigDecimal amountCollected;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
