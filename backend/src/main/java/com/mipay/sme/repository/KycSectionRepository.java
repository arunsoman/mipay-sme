package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KycSectionRepository extends JpaRepository<KycSection, String> {

    List<KycSection> findBySchemaVersionIdOrderBySortAsc(String schemaVersionId);
}
