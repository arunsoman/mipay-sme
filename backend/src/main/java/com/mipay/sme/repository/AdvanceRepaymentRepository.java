package com.mipay.sme.repository;

import com.mipay.sme.domain.credit.AdvanceRepayment;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface AdvanceRepaymentRepository extends JpaRepository<AdvanceRepayment, String> {

    List<AdvanceRepayment> findByAdvanceIdOrderByCreatedAtDesc(String advanceId);

    /** Repaid totals grouped per merchant (for BalanceView owed) - one query. */
    @Query("select r.merchantId, sum(r.amount) from AdvanceRepayment r group by r.merchantId")
    List<Object[]> repaidSumGroupByMerchant();

    @Query("select coalesce(sum(r.amount), 0) from AdvanceRepayment r where r.advance.id = :advanceId")
    BigDecimal repaidTotalByAdvance(@Param("advanceId") String advanceId);

    @Query("select coalesce(sum(r.amount), 0) from AdvanceRepayment r where r.merchantId = :merchantId")
    BigDecimal repaidTotalByMerchant(@Param("merchantId") String merchantId);

    boolean existsByAdvanceIdAndCollectionDate(String advanceId, LocalDate collectionDate);

    /** Collections for a lender with merchant names joined once (L14, TC-ADV-03). */
    @Query("""
            select new com.mipay.sme.web.Dtos$CollectionRow(
                r.id, a.merchant.businessName, r.amount, r.eligibleSalesBase, r.collectionDate)
            from AdvanceRepayment r join r.advance a
            where a.lender.id = :lenderId
            order by r.collectionDate desc, r.createdAt desc
            """)
    List<Dtos.CollectionRow> findForLender(@Param("lenderId") String lenderId);
}
