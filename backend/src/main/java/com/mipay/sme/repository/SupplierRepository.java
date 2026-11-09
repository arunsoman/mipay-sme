package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.ApprovalStatus;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.web.Dtos;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, String> {

    /** Pessimistic row lock - serialises concurrent approvals (TC-RACE-13). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Supplier s where s.id = :id")
    Optional<Supplier> findWithLockById(@Param("id") String id);

    Optional<com.mipay.sme.domain.core.Supplier> findByUserId(String userId);

    Optional<Supplier> findByOwnerPhone(String ownerPhone);

    @Query("""
            select new com.mipay.sme.web.Dtos$SupplierApprovalRow(
                s.id, s.businessName, s.ownerName, s.ownerPhone, s.registrationNumber, s.approvalStatus, s.createdAt,
                s.kycRiskRating, s.kycNextReviewDate)
            from Supplier s
            where s.approvalStatus = :status
            order by s.createdAt
            """)
    List<Dtos.SupplierApprovalRow> findQueue(@Param("status") ApprovalStatus status);

    @Query("""
            select new com.mipay.sme.web.Dtos$SupplierApprovalRow(
                s.id, s.businessName, s.ownerName, s.ownerPhone, s.registrationNumber, s.approvalStatus, s.createdAt,
                s.kycRiskRating, s.kycNextReviewDate)
            from Supplier s
            order by s.businessName
            """)
    List<Dtos.SupplierApprovalRow> findAllRows();
}
