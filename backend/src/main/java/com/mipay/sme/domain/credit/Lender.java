package com.mipay.sme.domain.credit;

import com.mipay.sme.domain.Enums.LenderStatus;
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
import java.util.UUID;

/** Licensed lender with NAMFISA licence reference and product configuration (BC p.13). */
@Entity
@Table(name = "lenders")
@Getter @Setter
public class Lender {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "licence_ref")
    private String licenceRef;

    /** Owning portal user (lender staff login) - one lender per user for this sandbox. */
    @jakarta.persistence.Column(name = "user_id", length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LenderStatus status;

    /** Ops approval mirror (null = APPROVED, [H3]); ACTIVE/SUSPENDED status stays. */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status")
    private com.mipay.sme.domain.Enums.ApprovalStatus approvalStatus;

    /** Share of eligible daily receipts collected as repayment (BC p.11, E15). */
    @Column(name = "collection_rate", precision = 5, scale = 4)
    private BigDecimal collectionRate = new BigDecimal("0.10");

    /** Service margin on the principal - illustrative N$10,000 becomes N$10,500. */
    @Column(name = "service_fee_rate", precision = 5, scale = 4)
    private BigDecimal serviceFeeRate = new BigDecimal("0.05");

    @Column(name = "max_daily_deduction", precision = 18, scale = 2)
    private BigDecimal maxDailyDeduction = new BigDecimal("2000");

    /** Maximum principal the lender is willing to expose in total (rule 3 of the cap). */
    @Column(name = "lender_limit", precision = 18, scale = 2)
    private BigDecimal lenderLimit = new BigDecimal("25000");

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
