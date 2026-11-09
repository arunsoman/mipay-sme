package com.mipay.sme.domain.compliance;

import com.mipay.sme.domain.Enums.AmlStatus;
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

/** Transaction-monitoring alert (K02, BC p.10): self-transfers, circular B2B, velocity. */
@Entity
@Table(name = "aml_alerts")
@Getter @Setter
public class AmlAlert {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "alert_type", nullable = false)
    private String alertType;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", length = 36, nullable = false)
    private String entityId;

    @Column(name = "reference_id", length = 36)
    private String referenceId;

    @Column(columnDefinition = "varchar(1000)")
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AmlStatus status = AmlStatus.OPEN;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
