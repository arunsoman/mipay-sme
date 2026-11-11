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
 * Sandbox-only raw-token mirror ([H8/M8]): the real outbox is
 * notification_messages (no link). This table exists only in sandbox/UAT so
 * the sim reader can assemble the link; production never populates it.
 */
@Getter
@Setter
@Entity
@Table(name = "sim_outbox")
public class SimOutbox {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "purpose", nullable = false, length = 40)
    private String purpose;           // ACTIVATION

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "activation_token_id", length = 36)
    private String activationTokenId;

    @Column(name = "raw_token", length = 128)
    private String rawToken;          // sandbox-only

    @Column(name = "meta", length = 255)
    private String meta;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
