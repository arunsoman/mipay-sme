package com.mipay.sme.domain.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

import java.time.Instant;
import jakarta.persistence.PrePersist;

@Entity
@Table(name = "consent_withdrawals")
@Getter @Setter
public class ConsentWithdrawal {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "consent_id", length = 36, nullable = false)
    private String consentId;

    private String reason;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (withdrawnAt == null) withdrawnAt = Instant.now();
        if (createdAt == null) createdAt = Instant.now();
    }
}
