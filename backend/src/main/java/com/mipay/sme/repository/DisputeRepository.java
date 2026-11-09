package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.Dispute;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DisputeRepository extends JpaRepository<Dispute, String> {

    @Query("""
            select new com.mipay.sme.web.Dtos$DisputeRow(
                d.id, d.merchantId, d.payment.merchant.businessName, d.reason, d.details, d.status, d.createdAt)
            from Dispute d
            join d.payment p
            join p.merchant
            order by d.createdAt desc
            """)
    List<Dtos.DisputeRow> findAllRows();
}
