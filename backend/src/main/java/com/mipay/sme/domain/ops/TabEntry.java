package com.mipay.sme.domain.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tab_entries", indexes = @Index(columnList = "tab_id"))
@Getter @Setter
public class TabEntry {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "tab_id", length = 36, nullable = false)
    private String tabId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    private String description;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
