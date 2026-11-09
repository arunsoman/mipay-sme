package com.mipay.sme.domain.fees;

import com.mipay.sme.domain.Enums.FeeStatus;
import com.mipay.sme.domain.core.Merchant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Applied once per event; (fee_code, reference) unique enforces no double-charge (TC-RCN-03). */
@Entity
@Table(name = "fee_transactions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"fee_code", "reference"}),
       indexes = @Index(columnList = "merchant_id"))
@Getter @Setter
public class FeeTransaction {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Column(name = "fee_code", nullable = false)
    private String feeCode;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeeStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
