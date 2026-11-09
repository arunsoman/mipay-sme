package com.mipay.sme.domain.recon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** Exclusion rules for the advance eligibility base (C23, BC p.10/p.12). */
@Entity
@Table(name = "eligibility_rules")
@Getter @Setter
public class EligibilityRule {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "rule_code", unique = true, nullable = false)
    private String ruleCode;   // exclude_advance | exclude_self_transfer | exclude_circular

    @Column(columnDefinition = "varchar(500)")
    private String definition;

    @Column(columnDefinition = "boolean default true")
    private Boolean enabled = true;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
