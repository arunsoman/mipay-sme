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
import java.util.UUID;

/** One-tap order rule: level, supplier and quantity (M33, TC-OPS-03). */
@Entity
@Table(name = "reorder_rules", indexes = @Index(columnList = "merchant_id"))
@Getter @Setter
public class ReorderRule {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "merchant_id", length = 36, nullable = false)
    private String merchantId;

    @Column(name = "item_id", length = 36, nullable = false)
    private String itemId;

    @Column(name = "reorder_level", precision = 18, scale = 2)
    private BigDecimal reorderLevel;

    @Column(name = "preferred_supplier_id", length = 36)
    private String preferredSupplierId;

    @Column(name = "order_quantity", precision = 18, scale = 2)
    private BigDecimal orderQuantity;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
