package com.mipay.sme.domain.kyc;

import com.mipay.sme.domain.Enums.OrgType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One KYC application per organization: the JSON document plus the org's
 * place in the tree (type, parent, root-first path, depth) and isSuspended.
 *
 * isSuspended mirrors "an active hold exists on this org or any ancestor";
 * it is rewritten for the whole subtree on every suspend/lift (orgs never
 * move, so the stored path/depth never go stale).
 */
@Getter
@Setter
@Entity
@Table(name = "kyc_applications")
public class KycApplication {

    public enum Status { DRAFT, ENTRY_CHECK, VERIFYING, IN_REVIEW, APPROVED, REJECTED, BLOCKED, WITHDRAWN }

    /** Statuses that count as "open" for the one-open-application-per-org rule (§9). */
    public static final List<Status> OPEN_STATUSES =
            List.of(Status.DRAFT, Status.ENTRY_CHECK, Status.VERIFYING, Status.IN_REVIEW);

    @Id
    @Column(name = "id", length = 36)
    private String id;

    // ---- tree details ----
    // Postgres: V8 drops V7's inline UNIQUE and adds the partial "one OPEN
    // application per org" index (history stays); H2 ddl-auto mirrors this.
    @Column(name = "org_id", nullable = false, length = 36)
    private String orgId;

    @Column(name = "org_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private OrgType orgType;

    @Column(name = "org_name")
    private String orgName;

    @Column(name = "parent_org_id", length = 36)
    private String parentOrgId;

    /** Root-first org ids joined by '/', ending with this org. */
    @Column(name = "tree_path", nullable = false, length = 1024)
    private String treePath;

    /** Distance from the platform root (root = 0). */
    @Column(name = "depth", nullable = false)
    private int depth;

    @Column(name = "is_suspended", nullable = false)
    private boolean suspended;

    // ---- document + lifecycle ----
    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    // ---- master-driven columns (§9) ----
    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "licence_type", length = 16)
    private String licenceType;

    @Column(name = "subject_type", length = 16)
    private String subjectType;

    @Column(name = "entity_type", length = 32)
    private String entityType;

    @Column(name = "profile_id", length = 36)
    private String profileId;

    @Column(name = "schema_version_id", length = 36)
    private String schemaVersionId;

    @Column(name = "workflow_key", length = 64)
    private String workflowKey;

    @Column(name = "workflow_version")
    private Integer workflowVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "workflow_plan")
    private String workflowPlan;

    @Column(name = "current_stage", length = 64)
    private String currentStage;

    @Column(name = "submission_no", nullable = false)
    private int submissionNo = 1;

    @Column(name = "risk_rating", length = 16)
    private String riskRating;

    @Column(name = "next_review_date")
    private Instant nextReviewDate;

    @Column(name = "retain_until")
    private Instant retainUntil;

    @Column(name = "needs_upgrade", nullable = false)
    private boolean needsUpgrade;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "application_json", nullable = false)
    private String applicationJson;

    /** Frozen copy at submit time: what the reviewer approves is exactly what was submitted. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submitted_json")
    private String submittedJson;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "submitted_by", length = 36)
    private String submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Version
    @Column(name = "version")
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
