package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** An entry check or stage task: both the approval inbox and the history (§6). */
@Getter
@Setter
@Entity
@Table(name = "kyc_workflow_tasks")
public class KycTask extends KycIdEntity {

    public enum Kind { ENTRY_CHECK, STAGE }
    public enum Status { OPEN, DONE, CANCELLED }
    public enum Outcome { APPROVE, RETURN, REJECT, BLOCK, CONFIRM, SEND_BACK }

    @Column(name = "application_id", nullable = false, length = 36)
    private String applicationId;

    @Column(name = "submission_no", nullable = false)
    private int submissionNo;

    @Column(name = "task_kind", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Column(name = "stage_key", nullable = false, length = 64)
    private String stageKey;

    @Column(name = "stage_seq", nullable = false)
    private int stageSeq;

    /** {"relation":"PLATFORM","function":"OPS_REVIEWER"} or {"relation":"SELF_ORG","orgRole":"ADMIN"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actor_rule", nullable = false)
    private String actorRule;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "outcome", length = 16)
    private String outcome;

    @Column(name = "acted_by", length = 36)
    private String actedBy;

    @Column(name = "acted_at")
    private Instant actedAt;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
