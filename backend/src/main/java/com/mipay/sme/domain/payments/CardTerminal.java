package com.mipay.sme.domain.payments;

import com.mipay.sme.domain.Enums.TerminalStatus;
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

/** Paired POS terminal (M44). Test charge succeeds during pairing. */
@Entity
@Table(name = "card_terminals")
@Getter @Setter
public class CardTerminal {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(name = "terminal_id", nullable = false)
    private String terminalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TerminalStatus status;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
