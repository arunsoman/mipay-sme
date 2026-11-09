package com.mipay.sme.domain.payments;

import com.mipay.sme.domain.Enums.PaymentEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only payment events. Unique (payment, event_type, payload_hash)
 * makes a replayed callback detectable and ignorable (TC-ACC-04).
 */
@Entity
@Table(name = "payment_events",
       uniqueConstraints = @UniqueConstraint(columnNames = {"payment_id", "event_type", "payload_hash"}),
       indexes = @Index(columnList = "payment_id"))
@Getter @Setter
public class PaymentEvent {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "payment_id", length = 36, nullable = false)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private PaymentEventType eventType;

    @Column(name = "payload_hash")
    private String payloadHash;

    @Column(columnDefinition = "varchar(500)")
    private String meta;

    @Column(columnDefinition = "boolean default true")
    private Boolean simulated = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
