package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycSearchIndexRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KycSearchIndexRepository extends JpaRepository<KycSearchIndexRow, KycSearchIndexRow.PK> {

    List<KycSearchIndexRow> findByApplicationId(String applicationId);

    List<KycSearchIndexRow> findByCanonicalTagAndHmac(String canonicalTag, String hmac);

    boolean existsByCanonicalTagAndHmacAndApplicationIdNot(String canonicalTag, String hmac, String applicationId);

    long deleteByApplicationIdAndCanonicalTag(String applicationId, String canonicalTag);
}
