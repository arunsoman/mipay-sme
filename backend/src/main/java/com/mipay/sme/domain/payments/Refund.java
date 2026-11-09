package com.mipay.sme.domain.payments;

import com.mipay.sme.domain.Enums.RefundStatus;
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

/** Refund of a customer payment; netted out of the advance eligibility base (TC-ADV-04). */
@Entity
@Table(name = "refunds", indexes = @Index(columnList = "payment_id"))
@Getter @Setter
public class Refund {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "payment_id", length = 36, nullable = false)
    private String paymentId;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
