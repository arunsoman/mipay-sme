package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.HoldStatus;
import com.mipay.sme.domain.ledger.BalanceHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface BalanceHoldRepository extends JpaRepository<BalanceHold, String> {

    List<BalanceHold> findByMerchantIdAndStatus(String merchantId, HoldStatus status);

    /** Active hold total per merchant - one group-by query. */
    @Query("select h.merchantId, sum(h.amount) from BalanceHold h where h.status = :status group by h.merchantId")
    List<Object[]> holdsSumByMerchant(@Param("status") HoldStatus status);

    /** Stale holds for reconciliation (C22). */
    List<BalanceHold> findByStatusAndExpiresAtBefore(HoldStatus status, Instant now);
}
