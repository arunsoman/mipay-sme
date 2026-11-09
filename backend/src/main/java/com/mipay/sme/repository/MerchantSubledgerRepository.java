package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.LedgerEntryType;
import com.mipay.sme.domain.Enums.SubledgerSource;
import com.mipay.sme.domain.Enums.SubledgerStatus;
import com.mipay.sme.domain.ledger.MerchantSubledgerEntry;
import com.mipay.sme.web.Dtos;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Mirror ledger per merchant (BC p.12). Lists use a JPQL constructor projection
 * (single query, no entity hydration -> no N+1, TC-PERF-03); sums use aggregate queries.
 */
public interface MerchantSubledgerRepository extends JpaRepository<MerchantSubledgerEntry, String> {

    List<MerchantSubledgerEntry> findBySourceRefIdAndSource(String sourceRefId, SubledgerSource source);

    List<MerchantSubledgerEntry> findAllByOwnerIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String ownerId, Instant from, Instant to);

    @Query("""
            select new com.mipay.sme.web.Dtos$LedgerRow(e.id, str(e.entryType), e.amount, e.currency,
                    str(e.source), str(e.status), coalesce(e.sourceRefId, ''), e.createdAt)
            from MerchantSubledgerEntry e
            where e.ownerId = :ownerId
            order by e.createdAt desc
            """)
    List<Dtos.LedgerRow> findByOwnerIdOrderByCreatedAtDesc(@Param("ownerId") String ownerId, Pageable pageable);

    @Query("""
            select e.ownerId, sum(case when e.entryType = :credit then e.amount else -e.amount end)
            from MerchantSubledgerEntry e
            where e.status = :status
            group by e.ownerId
            """)
    List<Object[]> netByStatusGroupOwner(@Param("status") SubledgerStatus status,
                                         @Param("credit") LedgerEntryType credit);
}
