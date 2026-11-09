package com.mipay.sme.domain.ledger;

import com.mipay.sme.domain.Enums.WithdrawalStatus;
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

/** Merchant cash-out; blocked while reconciliation has unexplained breaks (BC p.18). */
@Entity
@Table(name = "withdrawals", indexes = @Index(columnList = "merchant_id"))
@Getter @Setter
public class Withdrawal {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "bank_reference")
    private String bankReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalStatus status;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (requestedAt == null) requestedAt = Instant.now();
    }
}
