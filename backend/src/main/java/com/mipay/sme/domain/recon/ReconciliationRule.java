package com.mipay.sme.domain.recon;

import com.mipay.sme.domain.Enums.QrStatus;
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
import java.util.UUID;

/** Match keys, tolerance, schedule (C20-C22, BC p.18). */
@Entity
@Table(name = "reconciliation_rules")
@Getter @Setter
public class ReconciliationRule {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "match_key")
    private String matchKey;          // issuer_reference | idempotency_key

    @Column(precision = 18, scale = 2)
    private BigDecimal tolerance;

    @Column(name = "break_category")
    private String breakCategory;

    @Column(name = "schedule_cron")
    private String scheduleCron;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QrStatus status;          // ACTIVE / REVOKED used as ENABLED/DISABLED

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
