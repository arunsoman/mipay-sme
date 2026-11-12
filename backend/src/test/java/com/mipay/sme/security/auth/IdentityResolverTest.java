package com.mipay.sme.security.auth;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.service.OrgAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityResolverTest {
    @Test void rejectsUnknownDisabledAndMismatchedLinksAndHeldOrgs() {
        IdentityLinkRepository links = mock(IdentityLinkRepository.class);
        UserRepository users = mock(UserRepository.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        OrgAccess access = mock(OrgAccess.class);
        RbacApplicantClient applicant = mock(RbacApplicantClient.class);
        IdentityResolver resolver = new IdentityResolver(links, users, orgs, access, applicant);
        assertThrows(IdentityResolver.IdentityRejectedException.class,
                () -> resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"), 1, "sid", "bff"));

        IdentityLink link = new IdentityLink();
        link.setId("link1"); link.setIssuer("iss"); link.setSubject("sub"); link.setUserId("u1");
        link.setStatus(IdentityLink.Status.DISABLED); link.setIdpEnabled(true);
        when(links.findByIssuerAndSubject("iss", "sub")).thenReturn(Optional.of(link));
        assertThrows(IdentityResolver.IdentityRejectedException.class,
                () -> resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"), 1, "sid", "bff"));
        link.setStatus(IdentityLink.Status.ACTIVE);
        assertThrows(IdentityResolver.IdentityRejectedException.class,
                () -> resolver.resolve("iss", "sub", "other", "aal2", List.of("totp"), 1, "sid", "bff"));

        User user = new User();
        user.setId("u1"); user.setStatus(Enums.UserStatus.ACTIVE); user.setOrgId("org1");
        user.setRole(Enums.UserRole.MERCHANT);
        when(users.findById("u1")).thenReturn(Optional.of(user));
        Organization org = new Organization();
        org.setId("org1"); org.setOrgType(Enums.OrgType.MERCHANT); org.setEntityId("merchant1");
        when(orgs.findById("org1")).thenReturn(Optional.of(org));
        assertThrows(IdentityResolver.IdentityRejectedException.class,
                () -> resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"), 1, "sid", "bff"));
        when(access.isOperational("org1")).thenReturn(true);
        var principal = resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"), 1, "sid", "bff");
        assertEquals("merchant1", principal.merchantId());
        assertEquals("link1", principal.linkId());
        when(access.isOperational("org1")).thenReturn(false);
        when(access.isPendingApplicantOrg("org1")).thenReturn(true);
        when(applicant.hasActiveApplicantRole("u1", "org1")).thenReturn(true);
        assertEquals("u1", resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"),
                1, "sid", "bff", "PUT", "/api/kyc/orgs/org1").userId());
        assertThrows(IdentityResolver.IdentityRejectedException.class,
                () -> resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"),
                        1, "sid", "bff", "GET", "/api/merchant/account"));
        assertThrows(IdentityResolver.IdentityRejectedException.class,
                () -> resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"),
                        1, "sid", "bff", "PUT", "/api/kyc/orgs/other"));
        when(applicant.hasActiveApplicantRole("u1", "org1")).thenReturn(false);
        assertThrows(IdentityResolver.IdentityRejectedException.class,
                () -> resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"),
                        1, "sid", "bff", "PUT", "/api/kyc/orgs/org1"));
        user.setStatus(Enums.UserStatus.SUSPENDED);
        assertThrows(IdentityResolver.IdentityRejectedException.class,
                () -> resolver.resolve("iss", "sub", "u1", "aal2", List.of("totp"), 1, "sid", "bff"));
    }
}
