package com.mipay.sme.domain.payments;

import com.mipay.sme.domain.Enums.QrStatus;
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

@Entity
@Table(name = "qr_codes")
@Getter @Setter
public class QrCode {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(name = "qr_payload", nullable = false)
    private String qrPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QrStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
