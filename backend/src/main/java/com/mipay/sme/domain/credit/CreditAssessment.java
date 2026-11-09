package com.mipay.sme.domain.credit;

import com.mipay.sme.domain.Enums.AssessmentDecision;
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
import java.time.LocalDate;
import java.util.UUID;

/** Verified-records assessment over a 90-120 day window (BC p.10). */
@Entity
@Table(name = "credit_assessments", indexes = @Index(columnList = "merchant_id"))
@Getter @Setter
public class CreditAssessment {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lender_id", nullable = false)
    private Lender lender;

    @Column(name = "consent_id", length = 36)
    private String consentId;

    @Column(name = "window_start")
    private LocalDate windowStart;

    @Column(name = "window_end")
    private LocalDate windowEnd;

    @Column(name = "eligible_total", precision = 18, scale = 2)
    private BigDecimal eligibleTotal;

    @Column(name = "eligible_trading_days")
    private Integer eligibleTradingDays;

    @Column(name = "avg_monthly_net_receipts", precision = 18, scale = 2)
    private BigDecimal avgMonthlyNetReceipts;

    @Column(precision = 5, scale = 0)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentDecision decision;

    @Column(name = "reason_codes", columnDefinition = "varchar(500)")
    private String reasonCodes;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
