package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.FeeStatus;
import com.mipay.sme.domain.fees.FeeTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FeeTransactionRepository extends JpaRepository<FeeTransaction, String> {

    boolean existsByFeeCodeAndReference(String feeCode, String reference);

    Optional<FeeTransaction> findByFeeCodeAndReference(String feeCode, String reference);

    /** Daily fee totals per code for fee reports (T13) - one aggregate query. */
    @Query("""
            select f.feeCode, sum(f.amount)
            from FeeTransaction f
            where f.status = :status and f.createdAt >= :from and f.createdAt < :to
            group by f.feeCode
            """)
    List<Object[]> totalsPerCode(@Param("status") FeeStatus status,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    @Query("select coalesce(sum(f.amount), 0) from FeeTransaction f where f.feeCode = :feeCode and f.status = :status")
    BigDecimal totalPerCode(@Param("feeCode") String feeCode, @Param("status") FeeStatus status);
}
