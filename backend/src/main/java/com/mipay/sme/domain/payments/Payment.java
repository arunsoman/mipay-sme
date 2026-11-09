package com.mipay.sme.domain.payments;

import com.mipay.sme.domain.Enums.PaymentChannel;
import com.mipay.sme.domain.Enums.PaymentStatus;
import com.mipay.sme.domain.core.Merchant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Customer payment received by QR / short code / card (UAT SB p.9). */
@Entity
@Table(name = "payments", indexes = {@Index(columnList = "merchant_id"), @Index(columnList = "status")})
@Getter @Setter
public class Payment {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    /** Payer phone or reference; equal to merchant ownerPhone means a suspicious self-transfer. */
    @Column(name = "payer_ref")
    private String payerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentChannel channel;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "NAD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "idempotency_key", unique = true, nullable = false)
    private String idempotencyKey;

    /** Optional single stock item sold - drives a SALE stock movement (TC-OPS-01). */
    @Column(name = "item_id", length = 36)
    private String itemId;

    @Column(name = "item_quantity", precision = 18, scale = 2)
    private BigDecimal itemQuantity;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
