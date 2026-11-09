package com.mipay.sme.domain.fees;

import com.mipay.sme.domain.Enums.CostBasis;
import com.mipay.sme.domain.Enums.RateType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Tariff with explicit contracted-vs-provision labelling (C02, BC p.15):
 * the N$0.40 switching fee is a budgeting provision, the N$0.10 sponsor
 * markup is unquoted - never presented as contracted tariffs.
 */
@Entity
@Table(name = "fee_tariffs")
@Getter @Setter
public class FeeTariff {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(nullable = false, unique = true)
    private String code;                     // card_service etc.

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false)
    private RateType rateType;

    @Column(name = "tariff_value", nullable = false, precision = 8, scale = 4)
    private BigDecimal value;

    /** External bundled acquirer cost, e.g. 2.20% (contribution = billed - cost). */
    @Column(name = "external_cost", precision = 8, scale = 4)
    private BigDecimal externalCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_basis", nullable = false)
    private CostBasis costBasis;

    @Column(columnDefinition = "varchar(300)")
    private String note;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
