package com.mipay.sme.domain.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "vintage_analysis")
@Getter @Setter
public class VintageAnalysis {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "lender_id", length = 36, nullable = false)
    private String lenderId;

    @Column(name = "cohort_month")
    private LocalDate cohortMonth;

    @Column(precision = 18, scale = 2)
    private BigDecimal originations;

    @Column(precision = 18, scale = 2)
    private BigDecimal losses;

    @Column(name = "loss_rate", precision = 5, scale = 4)
    private BigDecimal lossRate;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
