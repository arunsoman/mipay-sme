package com.mipay.sme.domain.ops;

import com.mipay.sme.domain.Enums.KhataEntryType;
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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "khata_entries", indexes = @Index(columnList = "khata_id"))
@Getter @Setter
public class KhataEntry {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "khata_id", length = 36, nullable = false)
    private String khataId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private KhataEntryType entryType;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
