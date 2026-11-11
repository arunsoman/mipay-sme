package com.mipay.sme.domain.org;

import com.mipay.sme.domain.Enums;
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

/** Delegated onboarding review request; a pessimistic lock guards the decision. */
@Getter
@Setter
@Entity
@Table(name = "onboarding_requests")
public class OnboardingRequest {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "kind", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Enums.OnboardingKind kind;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Enums.OnboardingStatus status;

    @Column(name = "org_id", nullable = false, length = 36)
    private String orgId;

    @Column(name = "parent_org_id", length = 36)
    private String parentOrgId;

    /** The PENDING admin user created with the submission. */
    @Column(name = "admin_user_id", length = 36)
    private String adminUserId;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Column(name = "requested_by_role", length = 36)
    private String requestedByRole;

    @Column(name = "requested_by_org_id", length = 36)
    private String requestedByOrgId;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "meta", length = 255)
    private String meta;

    @Column(name = "created_at")
    private Instant createdAt;

    @jakarta.persistence.Version
    @Column(name = "version")
    private long version;

    /** Independence rule enforced at the DB level too, so H2 tests exercise it ([M4]). */
    @org.hibernate.annotations.Check(name = "chk_onboarding_reviewer_not_requester",
            constraints = "reviewed_by is null or reviewed_by <> requested_by")

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
