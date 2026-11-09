package com.mipay.sme.domain.compliance;

import com.mipay.sme.domain.Enums.DsarStatus;
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

/** Data subject access request export (K04, BC p.12). */
@Entity
@Table(name = "dsar_requests")
@Getter @Setter
public class DsarRequest {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DsarStatus status = DsarStatus.RECEIVED;

    @Column(columnDefinition = "varchar(500)")
    private String payloadRef;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (requestedAt == null) requestedAt = Instant.now();
    }
}
