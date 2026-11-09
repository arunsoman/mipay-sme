package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.KhataEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface KhataEntryRepository extends JpaRepository<KhataEntry, String> {

    List<KhataEntry> findByKhataIdOrderByCreatedAtDesc(String khataId);

    /** Outstanding total per khata account - one aggregate query for the list screen. */
    @Query("""
            select e.khataId, sum(case when e.entryType = com.mipay.sme.domain.Enums.KhataEntryType.CHARGE then e.amount else -e.amount end)
            from KhataEntry e
            where e.settledAt is null
            group by e.khataId
            """)
    List<Object[]> outstandingGroupByKhata();
}
