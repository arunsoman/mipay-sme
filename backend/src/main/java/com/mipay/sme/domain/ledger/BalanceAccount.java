package com.mipay.sme.domain.ledger;

import com.mipay.sme.domain.Enums.HoldStatus;
import com.mipay.sme.domain.Enums.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One balance account per merchant/supplier. The row is locked pessimistically
 * during money moves so concurrent invoices cannot both spend the same funds
 * (TC-B2B-08).
 */
@Entity
@Table(name = "balance_accounts", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_type", "owner_id"}))
@Getter @Setter
public class BalanceAccount {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private OwnerType ownerType;

    @Column(name = "owner_id", length = 36, nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String currency = "NAD";

    @Enumerated(EnumType.STRING)
    private com.mipay.sme.domain.Enums.AccountStatus status;

    @Column(name = "issuer_account_ref")
    private String issuerAccountRef;

    @Column(name = "legal_holder")
    private String legalHolder;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
