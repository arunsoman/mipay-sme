package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.PaymentStatus;
import com.mipay.sme.domain.payments.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Page<Payment> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    Optional<Payment> findByIdAndMerchantId(String id, String merchantId);

    /** Detail DTO source: fetch-joins the merchant name (open-in-view is OFF). */
    @Query("select p from Payment p join fetch p.merchant where p.id = :id and p.merchant.id = :merchantId")
    Optional<Payment> findDetailWithMerchant(@Param("id") String id, @Param("merchantId") String merchantId);

    /** Pessimistic row lock - serialises concurrent bank callbacks per payment (TC-RACE-01). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findWithLockById(@Param("id") String id);

    boolean existsByIdempotencyKey(String idempotencyKey);

    long countByMerchantIdAndCreatedAtAfter(String merchantId, Instant after);

    /** Platform-wide confirmed sum for settlements (T12) - single aggregate. */
    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.status = :status and p.createdAt >= :from and p.createdAt < :to")
    java.math.BigDecimal confirmedSumPlatform(@Param("status") PaymentStatus status,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    /** Confirmed payments in a window - feeds eligibility and daily sweeps (BC p.10). */
    @Query("select p from Payment p where p.merchant.id = :merchantId and p.status = :status and p.createdAt >= :from and p.createdAt < :to")
    List<Payment> confirmedInRange(@Param("merchantId") String merchantId,
                                   @Param("status") PaymentStatus status,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to);

    /** Today's confirmed sales for the dashboard - single aggregate. */
    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.merchant.id = :merchantId and p.status = :status and p.createdAt >= :from and p.createdAt < :to")
    java.math.BigDecimal confirmedSumInRange(@Param("merchantId") String merchantId,
                                             @Param("status") PaymentStatus status,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    /** Ops list with merchant name in one query (T17). */
    @Query(value = "select p from Payment p order by p.createdAt desc")
    List<Payment> latestForOps();
}
