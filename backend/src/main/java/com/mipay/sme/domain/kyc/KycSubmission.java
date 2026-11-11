package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A frozen copy of the document per submission; replaces submitted_json (§9). */
@Getter
@Setter
@Entity
@Table(name = "kyc_application_submissions")
public class KycSubmission extends KycIdEntity {

    @Column(name = "application_id", nullable = false, length = 36)
    private String applicationId;

    @Column(name = "submission_no", nullable = false)
    private int submissionNo;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "doc", nullable = false)
    private String doc;

    @Column(name = "submitted_by", length = 36)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
}
