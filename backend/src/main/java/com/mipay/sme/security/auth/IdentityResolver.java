package com.mipay.sme.security.auth;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.OrgAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Resolves the verified gateway identity against current core state on every request. */
@Service
@RequiredArgsConstructor
public class IdentityResolver {
    private final IdentityLinkRepository links;
    private final UserRepository users;
    private final OrganizationRepository orgs;
    private final OrgAccess orgAccess;
    private final RbacApplicantClient applicantRoles;

    @Transactional(readOnly = true)
    public Current.Principal resolve(String issuer, String subject, String uid, String acr,
                                     List<String> amr, long authTime, String sid, String clientId) {
        return resolve(issuer, subject, uid, acr, amr, authTime, sid, clientId, "", "");
    }

    @Transactional(readOnly = true)
    public Current.Principal resolve(String issuer, String subject, String uid, String acr,
                                     List<String> amr, long authTime, String sid, String clientId,
                                     String method, String path) {
        if (blank(issuer) || blank(subject) || blank(uid)) throw new IdentityRejectedException();
        IdentityLink link = links.findByIssuerAndSubject(issuer, subject).orElseThrow(IdentityRejectedException::new);
        if (link.getStatus() != IdentityLink.Status.ACTIVE || !link.isIdpEnabled() || !uid.equals(link.getUserId()))
            throw new IdentityRejectedException();
        User user = users.findById(uid).orElseThrow(IdentityRejectedException::new);
        if (user.getStatus() != Enums.UserStatus.ACTIVE || blank(user.getOrgId())) throw new IdentityRejectedException();
        Organization org = orgs.findById(user.getOrgId()).orElseThrow(IdentityRejectedException::new);
        if (!orgAccess.isOperational(org.getId()) &&
                !(orgAccess.isPendingApplicantOrg(org.getId()) && applicantRoute(method, path, org.getId()) &&
                        applicantRoles.hasActiveApplicantRole(uid, org.getId()))) throw new IdentityRejectedException();
        String entity = org.getEntityId();
        String type = org.getOrgType().name();
        return new Current.Principal(user.getId(), user.getRole() == null ? "" : user.getRole().name(),
                user.getName(), user.getPhone(),
                "MERCHANT".equals(type) ? entity : null,
                "SUPPLIER".equals(type) ? entity : null,
                "LENDER".equals(type) ? entity : null,
                org.getId(), type, user.getOrgRole() == null ? null : user.getOrgRole().name(),
                link.getId(), acr, amr == null ? List.of() : List.copyOf(amr),
                authTime > 0 ? Instant.ofEpochSecond(authTime) : null, sid, clientId);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    static boolean applicantRoute(String method, String path, String orgId) {
        if (method == null || path == null || orgId == null) return false;
        if (method.equals("GET")) {
            if (List.of("/api/orgs/me/kyc", "/api/kyc/orgs/me", "/api/kyc/schema").contains(path)) return true;
            if (path.startsWith("/api/kyc/lists/")) return true;
            if (path.equals("/api/kyc/schema/" + orgId)) return true;
        }
        if (List.of("GET", "PUT").contains(method) &&
                (path.equals("/api/kyc/orgs/" + orgId) || path.equals("/api/orgs/" + orgId + "/kyc"))) return true;
        return method.equals("POST") &&
                (path.equals("/api/kyc/orgs/" + orgId + "/submit") ||
                        path.equals("/api/orgs/" + orgId + "/kyc/submit"));
    }

    public static final class IdentityRejectedException extends RuntimeException { }
}
