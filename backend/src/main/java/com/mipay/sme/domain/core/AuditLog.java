package com.mipay.sme.domain.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit trail (BC p.18). Privileged actions, config changes and
 * every SIMULATED bank/lender event are recorded here with before/after
 * hashes where applicable.
 */
@Entity
@Table(name = "audit_logs")
@Getter @Setter
public class AuditLog {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "actor_id", length = 36)
    private String actorId;

    @Column(name = "actor_role")
    private String actorRole;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id", length = 36)
    private String entityId;

    @Column(name = "before_hash")
    private String beforeHash;

    @Column(name = "after_hash")
    private String afterHash;

    @Column(columnDefinition = "varchar(1000)")
    private String meta;

    @Column(columnDefinition = "boolean default false")
    private Boolean simulated = false;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
