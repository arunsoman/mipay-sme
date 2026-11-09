package com.mipay.sme.domain.core;

import com.mipay.sme.domain.Enums.ApprovalStatus;
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
@Table(name = "suppliers")
@Getter @Setter
public class Supplier {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "owner_phone")
    private String ownerPhone;

    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus;

    /** Risk-based KYC review cycle (KYC spec §5); set at approval. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_risk_rating")
    private com.mipay.sme.domain.Enums.KycRiskRating kycRiskRating;

    @Column(name = "kyc_next_review_date")
    private LocalDate kycNextReviewDate;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
