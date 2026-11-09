package com.mipay.sme.domain.compliance;

import com.mipay.sme.domain.Enums.KycCheckType;
import com.mipay.sme.domain.Enums.KycResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** KYC / AML / sanctions check on entities (K01, UAT SB p.8, BC p.10). */
@Entity
@Table(name = "kyc_checks")
@Getter @Setter
public class KycCheck {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "entity_type", nullable = false)
    private String entityType;      // merchant | supplier | owner

    @Column(name = "entity_id", length = 36, nullable = false)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false)
    private KycCheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycResult result;

    @Column(columnDefinition = "varchar(500)")
    private String details;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (checkedAt == null) checkedAt = Instant.now();
    }
}
