package com.mipay.sme.security.rbac;

/** Where the org (RBAC tenant) a request acts on comes from. */
public enum TenantSource {
    /** A path variable names the org, e.g. {@code /api/kyc/orgs/{id}}. */
    PATH,
    /** The org is the caller's own (merchant / supplier / lender / "me" endpoints). */
    SELF,
    /** The org is derived from an object (a task, a user, a request) - resolved by the service. */
    LOOKUP,
    /** Platform-wide operation (ops / admin): checked against the platform root. */
    NONE
}
