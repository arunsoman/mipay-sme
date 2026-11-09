package com.mipay.sme.domain.b2b;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** Product snapshot taken from the catalogue at order time. */
@Entity
@Table(name = "order_items", indexes = @Index(columnList = "order_id"))
@Getter @Setter
public class OrderItem {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "order_id", length = 36, nullable = false)
    private String orderId;

    @Column(name = "catalogue_item_id", length = 36)
    private String catalogueItemId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    private String unit;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitPrice;

    /** Optional link to the merchant's own inventory item, used for delivery movements. */
    @Column(name = "inventory_item_id", length = 36)
    private String inventoryItemId;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
