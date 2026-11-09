package com.mipay.sme.domain.credit;

import com.mipay.sme.domain.core.Merchant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * One collection event: share of that day's eligible net receipts, capped by
 * remaining owed and the lender's max daily deduction (TC-ADV-03/05).
 */
@Entity
@Table(name = "advance_repayments", indexes = {@Index(columnList = "advance_id"), @Index(columnList = "merchant_id")})
@Getter @Setter
public class AdvanceRepayment {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advance_id", nullable = false)
    private Advance advance;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "source_payment_id", length = 36)
    private String sourcePaymentId;

    @Column(name = "eligible_sales_base", precision = 18, scale = 2)
    private BigDecimal eligibleSalesBase;

    @Column(name = "collection_date")
    private java.time.LocalDate collectionDate;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
