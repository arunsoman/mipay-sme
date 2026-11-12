package com.mipay.sme.repository;

import com.mipay.sme.domain.core.IdentityLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IdentityLinkRepository extends JpaRepository<IdentityLink, String> {
    Optional<IdentityLink> findByIssuerAndSubject(String issuer, String subject);
    List<IdentityLink> findByUserId(String userId);
    Optional<IdentityLink> findByUserIdAndProviderAndStatusNot(String userId, String provider, IdentityLink.Status status);
}
