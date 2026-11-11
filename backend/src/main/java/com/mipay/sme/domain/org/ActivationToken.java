package com.mipay.sme.domain.org;

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
 * Activation token ([C4/H8]): only the SHA-256 of the 32-byte raw token is
 * stored; the raw bytes leave the system once, via the sandbox outbox only.
 */
@Getter
@Setter
@Entity
@Table(name = "activation_tokens")
public class ActivationToken {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "issued_by", length = 36)
    private String issuedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
