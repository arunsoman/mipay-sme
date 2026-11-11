package com.mipay.sme.domain.org;

import com.mipay.sme.domain.Enums.BankAccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Receiving bank account, org-scoped since V4 (the SUPPLIER entity's org, and
 * later any org type that needs one). Change requests create a new row with
 * status PENDING_APPROVAL; approval requires an approver that differs from the
 * requester (UAT SB p.8, TC-ONB-03, chk_bank_approver_not_requester).
 */
@Entity
@Table(name = "org_bank_accounts")
@Getter
@Setter
public class OrgBankAccount {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization org;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "account_holder", nullable = false)
    private String accountHolder;

    @Enumerated(EnumType.STRING)
    private BankAccountStatus status;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "request_note")
    private String requestNote;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
