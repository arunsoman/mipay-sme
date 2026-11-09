package com.mipay.sme.domain.core;

import com.mipay.sme.domain.Enums.ApprovalStatus;
import com.mipay.sme.domain.Enums.KycStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "merchants")
@Getter @Setter
public class Merchant {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "business_type")
    private String businessType;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "owner_id_number")
    private String ownerIdNumber;

    /** Denormalised from users.phone at registration for self-transfer checks (BC p.10). */
    @Column(name = "owner_phone")
    private String ownerPhone;

    @Enumerated(EnumType.STRING)
    private KycStatus kycStatus;

    /** Risk-based review cycle (KYC spec §5); set at approval, drives the re-KYC date. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_risk_rating")
    private com.mipay.sme.domain.Enums.KycRiskRating kycRiskRating;

    @Column(name = "kyc_next_review_date")
    private LocalDate kycNextReviewDate;

    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
