package com.mipay.sme.domain.ledger;

import com.mipay.sme.domain.Enums.HoldStatus;
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

/** Funds reserved for a supplier payment; released on failure/timeout expiry (UAT SB p.15). */
@Entity
@Table(name = "balance_holds", indexes = @Index(columnList = "merchant_id"))
@Getter @Setter
public class BalanceHold {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
