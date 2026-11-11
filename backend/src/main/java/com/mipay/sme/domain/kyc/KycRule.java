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

/** A cross-field validation rule; validator_key names a bean in the fixed registry (§3.3). */
@Getter
@Setter
@Entity
@Table(name = "kyc_rules")
public class KycRule extends KycIdEntity {

    public enum Phase { SAVE, SUBMIT }
    public enum Severity { ERROR, WARNING }

    @Column(name = "schema_version_id", nullable = false, length = 36)
    private String schemaVersionId;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "phase", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Phase phase;

    @Column(name = "validator_key", nullable = false, length = 64)
    private String validatorKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params")
    private String params;

    @Column(name = "message", nullable = false, length = 500)
    private String message;
}
