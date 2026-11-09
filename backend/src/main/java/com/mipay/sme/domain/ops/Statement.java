package com.mipay.sme.domain.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Generated merchant/supplier statement (T14, BC p.18). */
@Entity
@Table(name = "statements")
@Getter @Setter
public class Statement {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "owner_type", nullable = false)
    private String ownerType;

    @Column(name = "owner_id", length = 36, nullable = false)
    private String ownerId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "file_ref")
    private String fileRef;

    @Column(name = "generated_by", length = 36)
    private String generatedBy;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (generatedAt == null) generatedAt = Instant.now();
    }
}
