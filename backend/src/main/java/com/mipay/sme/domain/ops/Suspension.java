package com.mipay.sme.domain.ops;

import com.mipay.sme.domain.Enums.SuspensionEntityType;
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

/** Suspension with reason recorded (T15, BC p.13/p.27). */
@Entity
@Table(name = "suspensions")
@Getter @Setter
public class Suspension {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private SuspensionEntityType entityType;

    @Column(name = "entity_id", length = 36, nullable = false)
    private String entityId;

    @Column(nullable = false)
    private String reason;

    @Column(name = "suspended_by", length = 36)
    private String suspendedBy;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (suspendedAt == null) suspendedAt = Instant.now();
    }

    public boolean isActive() {
        return liftedAt == null;
    }
}
