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
 * Outbox message ([H8]): NEVER carries the activation link or the raw token —
 * only the activationTokenId. The link is assembled when the message is
 * dispatched, or by the sandbox outbox reader ([M8]).
 */
@Getter
@Setter
@Entity
@Table(name = "notification_messages")
public class NotificationMessage {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(nullable = false, length = 10)
    private String channel;          // EMAIL | SMS

    @Column(nullable = false, length = 255)
    private String recipient;

    @Column(name = "event_code", nullable = false, length = 40)
    private String eventCode;        // ACTIVATION | ACTIVATION_RESENT

    @Column(name = "template_vars", length = 512)
    private String templateVars;     // name/value pairs, never a link

    @Column(name = "activation_token_id", length = 36)
    private String activationTokenId;

    @Column(nullable = false, length = 16)
    private String status;           // QUEUED | SENT

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
