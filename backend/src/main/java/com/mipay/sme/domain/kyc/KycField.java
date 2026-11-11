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

import java.math.BigDecimal;

/** A field within a section; the master drives validation, sensitivity and masking (§3.2). */
@Getter
@Setter
@Entity
@Table(name = "kyc_fields")
public class KycField extends KycIdEntity {

    public enum DataType { TEXT, NUMBER, MONEY, PERCENT, DATE, BOOLEAN, ENUM, MULTI_ENUM, COUNTRY, EMAIL, PHONE, URL }
    public enum Required { ALWAYS, NEVER, WHEN }
    public enum DateRule { PAST, FUTURE, ANY }
    public enum Sensitivity { NONE, PII, SECRET }
    public enum Mask { LAST4, HIDE, YEAR_ONLY }
    public enum Normalize { PUNCT }

    @Column(name = "section_id", nullable = false, length = 36)
    private String sectionId;

    @Column(name = "\"key\"", nullable = false, length = 64)
    private String key;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "help_text", length = 500)
    private String helpText;

    @Column(name = "placeholder", length = 200)
    private String placeholder;

    @Column(name = "sort", nullable = false)
    private int sort;

    @Column(name = "data_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private DataType dataType;

    @Column(name = "list_code", length = 64)
    private String listCode;

    /** For list fields filtered by a parent list (bank -> branch). */
    @Column(name = "list_parent_field", length = 64)
    private String listParentField;

    @Column(name = "required", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Required required;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_when")
    private String requiredWhen;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_when")
    private String visibleWhen;

    @Column(name = "pattern", length = 200)
    private String pattern;

    @Column(name = "normalize", length = 16)
    @Enumerated(EnumType.STRING)
    private Normalize normalize;

    @Column(name = "min_len")
    private Integer minLen;

    @Column(name = "max_len")
    private Integer maxLen;

    @Column(name = "min_value", precision = 38, scale = 10)
    private BigDecimal minValue;

    @Column(name = "max_value", precision = 38, scale = 10)
    private BigDecimal maxValue;

    @Column(name = "date_rule", length = 16)
    @Enumerated(EnumType.STRING)
    private DateRule dateRule;

    /** e.g. industryVertical derived from mcc; the user can't edit it. */
    @Column(name = "derived_from", length = 200)
    private String derivedFrom;

    /** LEGAL_NAME, TAX_ID, UBO_ID_NUMBER, BANK_ACCOUNT_NUMBER, MCC, ... */
    @Column(name = "canonical_tag", length = 64)
    private String canonicalTag;

    @Column(name = "sensitivity", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Sensitivity sensitivity;

    @Column(name = "mask", length = 16)
    @Enumerated(EnumType.STRING)
    private Mask mask;
}
