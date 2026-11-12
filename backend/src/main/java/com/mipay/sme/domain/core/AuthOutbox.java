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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A provider command the core owes the IdP (and later other providers). Rows carry commands
 * only — never setup links or OTP values. A worker leases them after the committing core
 * transaction has completed. States: PENDING (runnable after available_at), RUNNING (leased,
 * bounded by lease_until), DONE (applied), FAILED (terminal; requeue is a manual operation).
 */
@Entity
@Table(name = "auth_outbox")
@Getter @Setter
public class AuthOutbox {
    public enum State { PENDING, RUNNING, DONE, FAILED }

    @Id @Column(length = 36)
    private String id;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(nullable = false, length = 64)
    private String provider;
    @Column(nullable = false, length = 64)
    private String command;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private State state;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "last_error", length = 500)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "dedupe_key", length = 255)
    private String dedupeKey;

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (state == null) state = State.PENDING;
        if (payload == null) payload = Map.of();
    }
}
