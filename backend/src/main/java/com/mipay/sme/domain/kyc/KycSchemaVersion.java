package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Published schema versions are immutable; a change mends a new version (§3.2). */
@Getter
@Setter
@Entity
@Table(name = "kyc_schema_versions")
public class KycSchemaVersion extends KycIdEntity {

    public enum Status { DRAFT, PUBLISHED, RETIRED }

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "published_at")
    private Instant publishedAt;
}
