package com.mipay.sme.domain.org;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Right of an org to onboard a given child type (ever-granted, no time window). */
@Getter
@Setter
@Entity
@Table(name = "org_rights")
public class OrgRight {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "org_id", nullable = false, length = 36)
    private String orgId;

    @Column(name = "target_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private OrgType targetType;

    /** DIRECT: the org may onboard the type itself. CEILING: subtree-wide upper bound. */
    @Column(name = "kind", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private Enums.OrgRightKind kind;

    @Column(name = "granted_by", length = 36)
    private String grantedBy;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (grantedAt == null) grantedAt = Instant.now();
    }
}
