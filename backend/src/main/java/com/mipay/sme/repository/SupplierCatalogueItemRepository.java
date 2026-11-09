package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.ItemStatus;
import com.mipay.sme.domain.core.SupplierCatalogueItem;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupplierCatalogueItemRepository extends JpaRepository<SupplierCatalogueItem, String> {

    /** Merchant-facing catalogue across all approved suppliers - one query (M13). */
    @Query("""
            select new com.mipay.sme.web.Dtos$CatalogueRow(
                c.id, c.supplier.id, c.supplier.businessName, c.productName, c.unit, c.unitPrice, c.status)
            from SupplierCatalogueItem c
            where c.status = :status
            order by c.supplier.businessName, c.productName
            """)
    List<Dtos.CatalogueRow> catalogueForMerchants(@Param("status") ItemStatus status);

    List<SupplierCatalogueItem> findBySupplierIdOrderByProductName(String supplierId);
}
