package com.mipay.sme.domain.org;

import com.mipay.sme.domain.Enums.OrgStatus;
import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.Enums.KycRiskRating;
import com.mipay.sme.domain.Enums.KycStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A node of the org tree (PLATFORM > MANUFACTURER > DISTRIBUTOR > SUPPLIER >
 * MERCHANT; LENDER hangs off PLATFORM). entity_id points at the business
 * entity row (merchants/suppliers/lenders); MANUFACTURER/DISTRIBUTOR carry no
 * entity — the org row IS the entity.
 */
@Getter
@Setter
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "org_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private OrgType orgType;

    @Column(name = "country", length = 2)
    private String country;

    /** Inherited from the parent at creation; the root is PSP (§9). */
    @Column(name = "licence_type", length = 8)
    private String licenceType;

    @Column(name = "name", nullable = false)
    private String name;

    /** Business entity row id for MERCHANT/SUPPLIER/LENDER orgs. */
    @Column(name = "entity_id", length = 36)
    private String entityId;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    /** Single source of truth for the org lifecycle ([C1/D2]). No suspended flag [D3/H4]. */
    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private OrgStatus lifecycle;

    /** KYC outcome of the org's own onboarding decision (org-typed entities: MANUFACTURER, DISTRIBUTOR, LENDER). */
    @Column(name = "kyc_status", length = 16)
    @Enumerated(EnumType.STRING)
    private KycStatus kycStatus;

    @Column(name = "kyc_risk_rating", length = 16)
    @Enumerated(EnumType.STRING)
    private KycRiskRating kycRiskRating;

    @Column(name = "kyc_next_review_date")
    private Instant kycNextReviewDate;

    @jakarta.persistence.Version
    @Column(name = "version")
    private long version;

    @Column(name = "onboarded_by", length = 36)
    private String onboardedBy;

    @Column(name = "onboarded_at")
    private Instant onboardedAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }

    /** Org policy: this org's users need MFA on the IdP (2fa-design §5.1; reconciled by AuthSyncService). */
    @Column(name = "mfa_required", nullable = false)
    private boolean mfaRequired = false;
}
