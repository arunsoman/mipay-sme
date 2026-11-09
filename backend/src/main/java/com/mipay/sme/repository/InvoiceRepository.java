package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.InvoiceStatus;
import com.mipay.sme.domain.b2b.Invoice;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, String> {

    @Query("""
            select new com.mipay.sme.web.Dtos$InvoiceListRow(
                i.id, i.invoiceNumber, i.uniqueReference, i.supplier.businessName,
                i.amount, i.currency, i.status, i.issuedAt)
            from Invoice i
            where i.merchant.id = :merchantId and i.status in :statuses
            order by i.issuedAt desc
            """)
    List<Dtos.InvoiceListRow> findForMerchant(@Param("merchantId") String merchantId,
                                              @Param("statuses") Collection<InvoiceStatus> statuses);

    @Query("""
            select new com.mipay.sme.web.Dtos$InvoiceListRow(
                i.id, i.invoiceNumber, i.uniqueReference, i.merchant.businessName,
                i.amount, i.currency, i.status, i.issuedAt)
            from Invoice i
            where i.supplier.id = :supplierId and i.status in :statuses
            order by i.issuedAt desc
            """)
    List<Dtos.InvoiceListRow> findForSupplier(@Param("supplierId") String supplierId,
                                              @Param("statuses") Collection<InvoiceStatus> statuses);

    Optional<Invoice> findByUniqueReference(String uniqueReference);

    boolean existsByUniqueReference(String uniqueReference);

    List<Invoice> findBySupplierIdOrderByIssuedAtDesc(String supplierId);
}
