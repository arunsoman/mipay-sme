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

/** A section of the form within a schema version (§3.2). */
@Getter
@Setter
@Entity
@Table(name = "kyc_sections")
public class KycSection extends KycIdEntity {

    public enum Kind { OBJECT, LIST, DOCUMENTS }

    @Column(name = "schema_version_id", nullable = false, length = 36)
    private String schemaVersionId;

    @Column(name = "\"key\"", nullable = false, length = 64)
    private String key;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "step", nullable = false)
    private int step;

    @Column(name = "kind", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Column(name = "min_items")
    private Integer minItems;

    @Column(name = "max_items")
    private Integer maxItems;

    /** Condition (§3.5) that hides the whole section. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_when")
    private String visibleWhen;

    @Column(name = "sort", nullable = false)
    private int sort;
}
