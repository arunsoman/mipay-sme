package com.mipay.sme.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Merchant balance account -> issuer account reference (E04, BC p.8/p.12). */
@Entity
@Table(name = "account_mappings")
@Getter @Setter
public class AccountMapping {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(name = "balance_account_id", length = 36, nullable = false)
    private String balanceAccountId;

    @Column(name = "connector_id", length = 36)   // absent until connector wiring is set (C30)
    private String connectorId;

    @Column(name = "issuer_account_ref", nullable = false)
    private String issuerAccountRef;

    @Column(name = "legal_holder", nullable = false)
    private String legalHolder;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
