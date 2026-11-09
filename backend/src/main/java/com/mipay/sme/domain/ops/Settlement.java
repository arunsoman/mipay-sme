package com.mipay.sme.domain.ops;

import com.mipay.sme.domain.Enums.SettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Daily settlement / net cycle (T12, BC p.27). */
@Entity
@Table(name = "settlements")
@Getter @Setter
public class Settlement {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    private String cycle;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
