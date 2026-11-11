package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The profile: which rules apply to (country, licence_type, subject_type) with
 * optional entity/org refinement; the most specific active match wins (§3.1).
 */
@Getter
@Setter
@Entity
@Table(name = "kyc_profiles")
public class KycProfile extends KycIdEntity {

    public enum LicenceType { PSP, EMI }
    public enum SubjectType { BUSINESS, INDIVIDUAL }

    @Column(name = "code", nullable = false, unique = true, length = 100)
    private String code;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "licence_type", nullable = false, length = 16)
    private String licenceType;

    @Column(name = "subject_type", nullable = false, length = 16)
    private String subjectType;

    @Column(name = "entity_type", length = 32)
    private String entityType;

    @Column(name = "org_type", length = 32)
    private String orgType;

    @Column(name = "schema_version_id", nullable = false, length = 36)
    private String schemaVersionId;

    @Column(name = "workflow_key", nullable = false, length = 64)
    private String workflowKey;

    @Column(name = "workflow_version", nullable = false)
    private int workflowVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "workflow_config")
    private String workflowConfig;

    @Column(name = "collection_provider", nullable = false, length = 64)
    private String collectionProvider;

    /** {"IDENTITY":"simulated","SANCTIONS":"simulated","PEP":"simulated"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_providers")
    private String checkProviders;

    @Column(name = "risk_scorer", nullable = false, length = 64)
    private String riskScorer;

    /** Tunables: uboThresholdPct, minOwnerAge, reviewMonths, checkTimeoutHours. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params")
    private String params;

    @Column(name = "ui_enabled", nullable = false)
    private boolean uiEnabled;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
}
