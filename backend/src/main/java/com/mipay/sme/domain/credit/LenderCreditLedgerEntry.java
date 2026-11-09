package com.mipay.sme.domain.credit;

import com.mipay.sme.domain.Enums.LedgerEntryType;
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
 * Third ledger (BC p.12): the lender's credit ledger. Amounts are deltas of
 * OWED: disbursement adds principal+fee owed, repayment subtracts collected
 * amounts. balance_after keeps the running owed balance.
 */
@Entity
@Table(name = "lender_credit_ledger", indexes = @Index(columnList = "advance_id"))
@Getter @Setter
public class LenderCreditLedgerEntry {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "advance_id", length = 36, nullable = false)
    private String advanceId;

    @Column(name = "lender_id", length = 36, nullable = false)
    private String lenderId;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private LedgerEntryType entryType;   // CREDIT = owe more, DEBIT = repaid

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
