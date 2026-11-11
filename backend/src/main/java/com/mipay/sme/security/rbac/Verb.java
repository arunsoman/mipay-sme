package com.mipay.sme.security.rbac;

/**
 * The fixed columns of the permission grid (docs/plans/fapi2-design.md, Appendix G).
 * A permission is one cell: {@code <resource>:<verb>}, e.g. {@code kyc.application:submit}.
 * Do not add a verb without a design decision: every role grid and SoD rule is defined
 * in terms of these ten.
 */
public enum Verb {
    READ, CREATE, MODIFY, DELETE, SUBMIT, AUTHORIZE, EXECUTE, EXPORT, ADMINISTER, SIMULATE;

    /** Lower-case form used in permission ids ({@code merchant.withdrawal:execute}). */
    public String id() {
        return name().toLowerCase();
    }
}
