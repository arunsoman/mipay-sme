package com.mipay.sme.security;

import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * Typed current-user access for services/controllers. The JWT filter turns
 * claims into a Principal object, so tenant scoping is always derived from
 * the token - never from path parameters (TC-SEC-01).
 */
public final class Current {

    private Current() { }

    public record Principal(String userId, String role, String name, String phone,
                            String merchantId, String supplierId, String lenderId,
                            String orgId, String orgType, String orgRole,
                            String linkId, String acr, java.util.List<String> amr,
                            java.time.Instant authTime, String sid, String clientId) {

        public Principal(String userId, String role, String name, String phone,
                         String merchantId, String supplierId, String lenderId,
                         String orgId, String orgType, String orgRole) {
            this(userId, role, name, phone, merchantId, supplierId, lenderId,
                    orgId, orgType, orgRole, null, null, java.util.List.of(), null, null, null);
        }

        /** Legacy shape (org fields null) used by sim/runAs helpers. */
        public Principal(String userId, String role, String name, String phone,
                         String merchantId, String supplierId, String lenderId) {
            this(userId, role, name, phone, merchantId, supplierId, lenderId, null, null, null);
        }

        public boolean isOrgAdmin() {
            return orgRole != null && "ADMIN".equals(orgRole);
        }
    }

    public static Principal get() {
        Authentication a = SecurityContextHolder.getContext() == null ? null : SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Principal p) {
            return p;
        }
        throw new NotFoundException("No authenticated principal on this path");
    }

    public static String merchantId() {
        Principal p = get();
        if (p.merchantId() == null) {
            throw new ForbiddenException("Token is not bound to a merchant account");
        }
        return p.merchantId();
    }

    public static String supplierId() {
        Principal p = get();
        if (p.supplierId() == null) {
            throw new ForbiddenException("Token is not bound to a supplier account");
        }
        return p.supplierId();
    }

    public static String lenderId() {
        Principal p = get();
        if (p.lenderId() == null) {
            throw new ForbiddenException("Token is not bound to a lender account");
        }
        return p.lenderId();
    }

    public static void requireRole(String role) {
        if (!role.equals(get().role())) {
            throw new ForbiddenException("Role " + role + " required");
        }
    }

    /** Re-entrant principal override for admin/sim endpoints that act on behalf of a role. */
    public static void runAs(String userId, String role, String merchantId, String supplierId, String lenderId,
                             Runnable action) {
        runAs(userId, role, userId, null, merchantId, supplierId, lenderId, null, null, null, action);
    }

    /** Full-shape override (org claims included) for org tests. */
    public static void runAs(String userId, String role, String name, String phone,
                             String merchantId, String supplierId, String lenderId,
                             String orgId, String orgType, String orgRole, Runnable action) {
        Principal original = null;
        Authentication auth = SecurityContextHolder.getContext() == null ? null
                : SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Principal p) original = p;
        SecurityContextHolder.getContext().setAuthentication(
                new PreAuthenticatedAuthenticationToken(
                        new Principal(userId, role, name, phone, merchantId, supplierId, lenderId,
                                orgId, orgType, orgRole),
                        null, java.util.List.of(() -> "ROLE_" + role)));
        try {
            action.run();
        } finally {
            SecurityContextHolder.getContext()
                    .setAuthentication(original == null ? null : authWith(original));
            if (original == null) SecurityContextHolder.getContext().setAuthentication(null);
        }
    }

    private static Authentication authWith(Principal p) {
        return new PreAuthenticatedAuthenticationToken(p, null, java.util.List.of(() -> "ROLE_" + p.role()));
    }
}
