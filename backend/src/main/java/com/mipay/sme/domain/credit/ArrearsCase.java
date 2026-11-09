package com.mipay.sme.domain.credit;

import com.mipay.sme.domain.Enums.BreakStatus;
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
@Table(name = "arrears_cases")
@Getter @Setter
public class ArrearsCase {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "advance_id", length = 36, nullable = false)
    private String advanceId;

    @Column(name = "lender_id", length = 36, nullable = false)
    private String lenderId;

    @Column(name = "days_past_due")
    private Integer daysPastDue;

    @Enumerated(EnumType.STRING)
    private BreakStatus status; // OPEN / RESOLVED

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (openedAt == null) openedAt = Instant.now();
    }
}
