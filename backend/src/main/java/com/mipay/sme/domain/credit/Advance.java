package com.mipay.sme.domain.credit;

import com.mipay.sme.domain.Enums.AdvanceStatus;
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

/**
 * Cash advance. Approval alone adds no money: availability only increases
 * after disbursement is confirmed by the issuer (TC-ADV-01/02).
 */
@Entity
@Table(name = "advances", indexes = {@Index(columnList = "merchant_id"), @Index(columnList = "lender_id")})
@Getter @Setter
public class Advance {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lender_id", nullable = false)
    private Lender lender;

    @Column(name = "assessment_id", length = 36)
    private String assessmentId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal requestedAmount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal principalAmount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal totalRepayment;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal repaymentRate;

    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdvanceStatus status;

    @Column(name = "funded_at")
    private Instant fundedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
