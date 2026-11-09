package com.mipay.sme.domain.b2b;

import com.mipay.sme.domain.Enums.InvoicePaymentRoute;
import com.mipay.sme.domain.Enums.InvoicePaymentStatus;
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

/**
 * Merchant payment of a supplier invoice from balance. Atomic: one debit, one
 * supplier credit, invoice marked paid (TC-B2B-02). Hold secures funds until
 * the bank answers; failure releases it (TC-B2B-04/07).
 */
@Entity
@Table(name = "invoice_payments", indexes = {@Index(columnList = "invoice_id"), @Index(columnList = "merchant_id")})
@Getter @Setter
public class InvoicePayment {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "invoice_id", length = 36, nullable = false)
    private String invoiceId;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(name = "supplier_id", length = 36, nullable = false)
    private String supplierId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_route", nullable = false)
    private InvoicePaymentRoute route;   // native IPP B2B excluded at go-live (BC p.9)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoicePaymentStatus status;

    @Column(name = "hold_id", length = 36)
    private String holdId;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "idempotency_key", unique = true, nullable = false)
    private String idempotencyKey;

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
