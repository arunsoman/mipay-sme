package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.Complaint;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ComplaintRepository extends JpaRepository<Complaint, String> {

    @Query("""
            select new com.mipay.sme.web.Dtos$ComplaintRow(
                c.id, c.merchant.businessName, c.supplier.businessName, c.category, c.description, c.status, c.assignedTo, c.createdAt)
            from Complaint c
            left join c.merchant
            left join c.supplier
            order by c.createdAt desc
            """)
    List<Dtos.ComplaintRow> findAllRows();
}
