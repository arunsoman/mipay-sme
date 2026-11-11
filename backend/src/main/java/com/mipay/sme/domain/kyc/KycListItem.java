package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A value within a choice list; values are deactivated, never deleted (§3.4). */
@Getter
@Setter
@Entity
@Table(name = "kyc_list_items")
public class KycListItem extends KycIdEntity {

    @Column(name = "list_code", nullable = false, length = 64)
    private String listCode;

    @Column(name = "\"value\"", nullable = false, length = 200)
    private String value;

    @Column(name = "label", nullable = false, length = 300)
    private String label;

    /** For dependent dropdowns (branch -> bank). */
    @Column(name = "parent_value", length = 200)
    private String parentValue;

    /** Plugin data: MCC vertical/risk, country FATF flags, branch codes, ... */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes")
    private String attributes;

    @Column(name = "sort", nullable = false)
    private int sort;

    @Column(name = "active", nullable = false)
    private boolean active;
}
