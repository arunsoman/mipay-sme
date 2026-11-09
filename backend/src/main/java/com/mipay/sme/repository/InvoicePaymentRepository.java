package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.InvoicePaymentStatus;
import com.mipay.sme.domain.b2b.InvoicePayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, String> {

    List<InvoicePayment> findByInvoiceIdOrderByCreatedAtDesc(String invoiceId);

    /** Batch for invoice lists - one query for all rows of the page (no N+1). */
    List<InvoicePayment> findByInvoiceIdIn(Collection<String> invoiceIds);

    @Query("select coalesce(sum(i.amount), 0) from InvoicePayment i where i.merchantId = :merchantId and i.status = :status and i.createdAt >= :from and i.createdAt < :to")
    BigDecimal confirmedSumInRange(@Param("merchantId") String merchantId,
                                  @Param("status") InvoicePaymentStatus status,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);

    List<InvoicePayment> findBySupplierIdOrderByCreatedAtDesc(String supplierId, org.springframework.data.domain.Pageable pageable);

    /** Platform-wide confirmed supplier-payment sum for settlements (T12). */
    @Query("select coalesce(sum(i.amount), 0) from InvoicePayment i where i.status = :status and i.createdAt >= :from and i.createdAt < :to")
    BigDecimal confirmedSumPlatform(@Param("status") InvoicePaymentStatus status,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);
}
