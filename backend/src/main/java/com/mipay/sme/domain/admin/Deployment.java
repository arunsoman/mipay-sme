package com.mipay.sme.domain.admin;

import com.mipay.sme.domain.Enums.DeployStatus;
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

/** Release versions and rollback evidence (X12, BC p.13). */
@Entity
@Table(name = "deployments")
@Getter @Setter
public class Deployment {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String environment;   // sandbox | uat | production

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeployStatus status;

    @Column(columnDefinition = "varchar(500)")
    private String notes;

    @Column(name = "deployed_by", length = 36)
    private String deployedBy;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (deployedAt == null) deployedAt = Instant.now();
    }
}
