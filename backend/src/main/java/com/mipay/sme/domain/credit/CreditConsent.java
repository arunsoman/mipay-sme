package com.mipay.sme.domain.credit;

import com.mipay.sme.domain.Enums.ConsentStatus;
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

import java.time.Instant;
import java.util.UUID;

/** Purpose-limited consent to share records with a named lender (BC p.12, UAT SB p.12). */
@Entity
@Table(name = "credit_consents", indexes = @Index(columnList = "merchant_id"))
@Getter @Setter
public class CreditConsent {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lender_id", nullable = false)
    private Lender lender;

    @Column(nullable = false)
    private String purpose;

    /** JSON field list; customer-level Khata excluded by default (BC p.12). */
    @Column(name = "data_fields", columnDefinition = "varchar(1000)")
    private String dataFields;

    @Column(name = "retention_period")
    private String retentionPeriod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentStatus status;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (grantedAt == null) grantedAt = Instant.now();
    }
}
