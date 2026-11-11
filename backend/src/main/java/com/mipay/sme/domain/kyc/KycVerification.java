package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One row per provider check; raw responses are stored encrypted (§7). */
@Getter
@Setter
@Entity
@Table(name = "kyc_verifications")
public class KycVerification extends KycIdEntity {

    public enum Status { PENDING, CLEAR, HIT, ERROR }

    @Column(name = "application_id", nullable = false, length = 36)
    private String applicationId;

    @Column(name = "submission_no", nullable = false)
    private int submissionNo;

    /** 'org' or e.g. 'ubos[1]'. */
    @Column(name = "subject", length = 120)
    private String subject;

    @Column(name = "check_type", nullable = false, length = 32)
    private String checkType;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "provider_ref", length = 120)
    private String providerRef;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "note", length = 500)
    private String note;
}
