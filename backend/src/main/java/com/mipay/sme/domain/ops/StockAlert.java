package com.mipay.sme.domain.ops;

import com.mipay.sme.domain.Enums.StockAlertType;
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

import java.time.Instant;
import java.util.UUID;

/** Low/out-of-stock alert raised when stock crosses the reorder level (TC-OPS-02). */
@Entity
@Table(name = "stock_alerts", indexes = @Index(columnList = "merchant_id"))
@Getter @Setter
public class StockAlert {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(name = "item_id", length = 36, nullable = false)
    private String itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false)
    private StockAlertType alertType;

    @Column(columnDefinition = "boolean default false")
    private Boolean resolved = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
