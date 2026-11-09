package com.mipay.sme.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** Per-merchant or default limits (C10, BC p.7/p.18). merchant_id null = platform default. */
@Entity
@Table(name = "limit_profiles")
@Getter @Setter
public class LimitProfile {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36)
    private String merchantId;

    @Column(precision = 18, scale = 2)
    private BigDecimal maxBalance;

    @Column(name = "daily_debit_cap", precision = 18, scale = 2)
    private BigDecimal dailyDebitCap;

    @Column(name = "withdrawal_cap", precision = 18, scale = 2)
    private BigDecimal withdrawalCap;

    @Column(name = "advance_cap", precision = 18, scale = 2)
    private BigDecimal advanceCap;

    @Column(name = "arrears_trigger_pct", precision = 5, scale = 4)
    private BigDecimal arrearsTriggerPct;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
