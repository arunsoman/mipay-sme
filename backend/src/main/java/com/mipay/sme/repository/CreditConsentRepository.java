package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.ConsentStatus;
import com.mipay.sme.domain.credit.CreditConsent;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CreditConsentRepository extends JpaRepository<CreditConsent, String> {

    Optional<CreditConsent> findFirstByMerchantIdAndLenderIdAndStatusOrderByGrantedAtDesc(String merchantId, String lenderId, ConsentStatus status);

    boolean existsByMerchantIdAndLenderIdAndStatus(String merchantId, String lenderId, ConsentStatus status);

    /** Lender consent queue with merchant names in one query (L02). */
    @Query("""
            select new com.mipay.sme.web.Dtos$ConsentRow(
                c.id, c.merchant.id, c.merchant.businessName, c.purpose, c.status, c.grantedAt)
            from CreditConsent c
            where c.lender.id = :lenderId and c.status = :status
            order by c.grantedAt desc
            """)
    List<Dtos.ConsentRow> consentsForLender(@Param("lenderId") String lenderId, @Param("status") ConsentStatus status);

    List<CreditConsent> findByMerchantIdOrderByGrantedAtDesc(String merchantId);

    /** Merchant view of own consents, same shape via one query (avoids proxy reads). */
    @Query("""
            select new com.mipay.sme.web.Dtos$ConsentRow(
                c.id, c.merchant.id, c.merchant.businessName, c.purpose, c.status, c.grantedAt)
            from CreditConsent c
            where c.merchant.id = :merchantId
            order by c.grantedAt desc
            """)
    List<Dtos.ConsentRow> rowsForMerchant(@Param("merchantId") String merchantId);
}
