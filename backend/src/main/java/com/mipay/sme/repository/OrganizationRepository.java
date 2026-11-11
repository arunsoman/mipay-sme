package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.org.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, String> {

    Optional<Organization> findByOrgTypeAndEntityId(OrgType orgType, String entityId);

    Optional<Organization> findByOrgType(OrgType orgType);

    Optional<Organization> findByEntityId(String entityId);

    List<Organization> findByParentId(String parentId);

    boolean existsByOrgTypeAndRegistrationNumber(OrgType orgType, String registrationNumber);

    /**
     * UNIQUE_IN_PLATFORM (§3.3): another active org (excluding `excludeOrgId` when given)
     * already uses a registration number that normalises to the given value.
     * Normalisation strips / - . and spaces (same as KycNormalizer.punct).
     */
    @Query("""
        select (count(o) > 0) from Organization o
        where upper(o.registrationNumber) is not null
          and replace(replace(replace(replace(
                  upper(o.registrationNumber), '/', ''), '-', ''), '.', ''), ' ', '') = :normalized
          and o.lifecycle = :status
          and (:excludeOrgId is null or o.id <> :excludeOrgId)
        """)
    boolean existsByNormalizedRegistrationNumber(@Param("normalized") String normalized,
                                                 @Param("excludeOrgId") String excludeOrgId,
                                                 @Param("status") com.mipay.sme.domain.Enums.OrgStatus status);
}
