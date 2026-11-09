package com.mipay.sme.domain.ledger;

import com.mipay.sme.domain.Enums.LedgerEntryType;
import com.mipay.sme.domain.Enums.OwnerType;
import com.mipay.sme.domain.Enums.SubledgerSource;
import com.mipay.sme.domain.Enums.SubledgerStatus;
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
 * MiPay mirrored subledger (BC p.12). Entries start PENDING_CONFIRMATION for
 * card flows and become CONFIRMED when the issuer confirms (UAT SB p.14).
 */
@Entity
@Table(name = "merchant_subledger", indexes = {
        @Index(columnList = "owner_id"),
        @Index(columnList = "source_ref_id")
})
@Getter @Setter
public class MerchantSubledgerEntry {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubledgerSource source;

    @Column(name = "source_ref_id", length = 36)
    private String sourceRefId;

    @Column(name = "issuer_ledger_id", length = 36)
    private String issuerLedgerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubledgerStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
