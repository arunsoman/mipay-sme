package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.WithdrawalStatus;
import com.mipay.sme.domain.ledger.Withdrawal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, String> {

    Page<Withdrawal> findByMerchantIdOrderByRequestedAtDesc(String merchantId, Pageable pageable);

    /** Withdrawn today vs the daily withdrawal cap (C10). */
    @Query("select coalesce(sum(w.amount), 0) from Withdrawal w where w.merchantId = :merchantId and w.status in :statuses and w.requestedAt >= :from")
    BigDecimal sumToday(@Param("merchantId") String merchantId,
                        @Param("statuses") Collection<WithdrawalStatus> statuses,
                        @Param("from") Instant from);
}
