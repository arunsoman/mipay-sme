package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycSchemaVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycSchemaVersionRepository extends JpaRepository<KycSchemaVersion, String> {

    List<KycSchemaVersion> findByCode(String code);

    Optional<KycSchemaVersion> findByCodeAndVersion(String code, int version);

    Optional<KycSchemaVersion> findByCodeAndVersionAndStatus(String code, int version, KycSchemaVersion.Status status);

    List<KycSchemaVersion> findByStatus(KycSchemaVersion.Status status);
}
