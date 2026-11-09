package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.ApprovalStatus;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, String> {

    Optional<Merchant> findByUserId(String userId);

    Optional<Merchant> findByOwnerPhone(String ownerPhone);

    /** Ops approval queue (T02) - single projection, no joined table needed. */
    @Query("""
            select new com.mipay.sme.web.Dtos$MerchantApprovalRow(
                m.id, m.businessName, m.ownerName, m.ownerPhone, m.businessType,
                m.registrationNumber, m.kycStatus, m.approvalStatus, m.createdAt,
                m.kycRiskRating, m.kycNextReviewDate)
            from Merchant m
            where m.approvalStatus = :status
            order by m.createdAt
            """)
    List<Dtos.MerchantApprovalRow> findQueue(@Param("status") ApprovalStatus status);

    @Query("""
            select new com.mipay.sme.web.Dtos$MerchantApprovalRow(
                m.id, m.businessName, m.ownerName, m.ownerPhone, m.businessType,
                m.registrationNumber, m.kycStatus, m.approvalStatus, m.createdAt,
                m.kycRiskRating, m.kycNextReviewDate)
            from Merchant m
            order by m.businessName
            """)
    List<Dtos.MerchantApprovalRow> findAllRows();
}
