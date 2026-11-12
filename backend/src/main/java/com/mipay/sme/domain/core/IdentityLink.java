package com.mipay.sme.domain.core;

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

/** A core user may have several credentials; this row binds one external subject to that user. */
@Entity
@Table(name = "identity_links")
@Getter @Setter
public class IdentityLink {
    public enum Status { PENDING, ACTIVE, DISABLED, REVOKED }

    @Id @Column(length = 36)
    private String id;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(nullable = false, length = 64)
    private String provider;
    @Column(nullable = false, length = 255)
    private String issuer;
    @Column(nullable = false, length = 255)
    private String subject;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Status status;
    @Column(name = "idp_enabled", nullable = false)
    private boolean idpEnabled;
    @Column(name = "max_aal", nullable = false, length = 16)
    private String maxAal = "AAL1";
    @Column(name = "created_by", length = 36)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "activated_at")
    private Instant activatedAt;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = Status.PENDING;
    }
}
