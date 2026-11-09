package com.mipay.sme.domain.b2b;

import com.mipay.sme.domain.Enums.InvoiceStatus;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
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

/** Supplier-issued invoice for a stock order; payment needs a unique reference (UAT SB p.10). */
@Entity
@Table(name = "invoices", indexes = {@Index(columnList = "merchant_id"), @Index(columnList = "supplier_id")})
@Getter @Setter
public class Invoice {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Column(name = "invoice_number", nullable = false)
    private String invoiceNumber;

    @Column(name = "unique_reference", unique = true, nullable = false)
    private String uniqueReference;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "NAD";

    /** Order this invoice was issued from (flat link, kept simple). */
    @Column(name = "order_id", length = 36)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (issuedAt == null) issuedAt = Instant.now();
    }
}
