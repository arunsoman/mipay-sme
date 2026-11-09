package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.AdvanceStatus;
import com.mipay.sme.domain.credit.Advance;
import com.mipay.sme.web.Dtos;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AdvanceRepository extends JpaRepository<Advance, String> {

    /** Pessimistic row lock - serialises concurrent funding replays (TC-RACE-03). */
    @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Advance a where a.id = :id")
    Optional<Advance> findWithLockById(@Param("id") String id);

    List<Advance> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    Optional<Advance> findFirstByMerchantIdAndStatusOrderByCreatedAtDesc(String merchantId, AdvanceStatus status);

    /** Lender portfolio list with merchant name in one query (L10). */
    @Query("""
            select new com.mipay.sme.web.Dtos$LenderAdvanceRow(
                a.id, a.merchant.businessName, a.merchant.id, a.principalAmount,
                a.totalRepayment - (select coalesce(sum(r.amount), 0) from AdvanceRepayment r where r.advance.id = a.id),
                str(a.status), a.fundedAt,
                '')
            from Advance a
            where a.lender.id = :lenderId
            order by a.createdAt desc
            """)
    List<Dtos.LenderAdvanceRow> findForLender(@Param("lenderId") String lenderId);

    Optional<Advance> findByIdAndMerchantId(String id, String merchantId);

    @Query("select coalesce(sum(a.totalRepayment), 0) from Advance a where a.lender.id = :lenderId and a.status in :statuses")
    BigDecimal owedTotalForLender(@Param("lenderId") String lenderId, @Param("statuses") Collection<AdvanceStatus> statuses);

    /** Total owed grouped per merchant - one query (used in BalanceView owed). */
    @Query("select a.merchant.id, sum(a.totalRepayment) from Advance a where a.lender.id = :lenderId and a.status in :statuses group by a.merchant.id")
    List<Object[]> owedGroupByMerchantForLender(@Param("lenderId") String lenderId, @Param("statuses") Collection<AdvanceStatus> statuses);

    @Query("select coalesce(sum(a.totalRepayment), 0) from Advance a where a.merchant.id = :merchantId and a.status in :statuses")
    BigDecimal owedTotalForMerchant(@Param("merchantId") String merchantId, @Param("statuses") Collection<AdvanceStatus> statuses);

    /** Owed grouped per merchant for BalanceView - avoids per-merchant queries. */
    @Query("select a.merchant.id, sum(a.totalRepayment) from Advance a where a.status in :statuses group by a.merchant.id")
    List<Object[]> owedSumGroupByMerchant(@Param("statuses") Collection<AdvanceStatus> statuses);

    List<Advance> findByLenderId(String lenderId);

    /** Advances in repayment per lender (sweep target set). */
    @Query("select a from Advance a where a.lender.id = :lenderId and a.status in :statuses")
    List<Advance> activeForLender(@Param("lenderId") String lenderId, @Param("statuses") Collection<AdvanceStatus> statuses);
}
