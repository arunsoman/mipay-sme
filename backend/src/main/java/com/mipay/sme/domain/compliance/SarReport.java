package com.mipay.sme.domain.compliance;

import com.mipay.sme.domain.Enums.SarStatus;
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

/** Suspicious activity report (K03, BC p.28). */
@Entity
@Table(name = "sar_reports")
@Getter @Setter
public class SarReport {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "alert_id", length = 36)
    private String alertId;

    private String reference;

    @Column(columnDefinition = "text")
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SarStatus status = SarStatus.DRAFT;

    @Column(name = "reported_by", length = 36)
    private String reportedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
