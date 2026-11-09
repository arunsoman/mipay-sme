package com.mipay.sme.domain.ledger;

import com.mipay.sme.domain.Enums.LedgerEntryType;
import com.mipay.sme.domain.Enums.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative issuer-side ledger entry (BC p.12). Idempotency key makes
 * duplicate callbacks/effects a no-op (BC p.18, UAT SB p.15).
 */
@Entity
@Table(name = "issuer_balance_ledger", indexes = @Index(columnList = "owner_id"))
@Getter @Setter
public class IssuerBalanceLedgerEntry {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "balance_account_id", length = 36, nullable = false)
    private String balanceAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private OwnerType ownerType;

    @Column(name = "owner_id", length = 36, nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private LedgerEntryType entryType;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "NAD";

    @Column(name = "issuer_reference")
    private String issuerReference;

    @Column(name = "idempotency_key", unique = true, nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
