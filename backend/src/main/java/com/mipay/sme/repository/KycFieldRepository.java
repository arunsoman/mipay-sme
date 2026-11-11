package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KycFieldRepository extends JpaRepository<KycField, String> {

    List<KycField> findBySectionIdOrderBySortAsc(String sectionId);

    List<KycField> findByCanonicalTag(String canonicalTag);
}
