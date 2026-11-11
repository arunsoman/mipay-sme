package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycProfileRepository extends JpaRepository<KycProfile, String> {

    Optional<KycProfile> findByCode(String code);

    List<KycProfile> findByActiveTrue();

    Optional<KycProfile> findByCountryAndLicenceTypeAndSubjectTypeAndOrgTypeAndActiveTrue(
            String country, String licenceType, String subjectType, String orgType);

    List<KycProfile> findByCountryAndLicenceTypeAndSubjectTypeAndActiveTrue(
            String country, String licenceType, String subjectType);
}
