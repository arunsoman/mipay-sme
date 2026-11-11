package com.mipay.sme.security;

import com.mipay.sme.common.ForbiddenException;

/**
 * Org-hierarchy guard [H5]: money-moving endpoints are ADMIN-only for users
 * bound to an org; platform-era users (orgRole == null) are unaffected.
 */
public final class MoneyGuard {

    private MoneyGuard() { }

    public static void requireAdmin(Current.Principal principal) {
        if (principal.orgRole() != null && !"ADMIN".equals(principal.orgRole())) {
            throw new ForbiddenException("ADMIN_REQUIRED",
                    "Money movement requires an org ADMIN ([H5])");
        }
    }
}
