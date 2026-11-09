package com.mipay.sme.domain.payments;

import com.mipay.sme.domain.Enums.QrStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import jakarta.persistence.PrePersist;

@Entity
@Table(name = "short_codes", uniqueConstraints = @UniqueConstraint(columnNames = "short_code"))
@Getter @Setter
public class ShortCode {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(name = "short_code", unique = true, nullable = false)
    private String shortCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QrStatus status;

    @Column(name = "created_at")
    private java.time.Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = java.time.Instant.now();
    }
}
