package com.mipay.sme.domain.ops;

import com.mipay.sme.domain.Enums.InventoryItemStatus;
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
import java.util.UUID;

/** Merchant inventory item (M30, UAT SB p.9). */
@Entity
@Table(name = "inventory_items", indexes = @Index(columnList = "merchant_id"))
@Getter @Setter
public class InventoryItem {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String name;

    private String unit;

    @Column(name = "reorder_level", precision = 18, scale = 2)
    private BigDecimal reorderLevel;

    @Column(name = "current_stock", precision = 18, scale = 2)
    private BigDecimal currentStock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InventoryItemStatus status = InventoryItemStatus.ACTIVE;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
