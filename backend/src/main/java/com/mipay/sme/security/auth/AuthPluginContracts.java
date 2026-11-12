package com.mipay.sme.security.auth;

import java.time.Instant;
import java.util.List;

/** Core-owned credential-provider boundary. A provider never decides whether a user is legitimate. */
public final class AuthPluginContracts {
    private AuthPluginContracts() { }

    public enum ResetScope { LINK, MFA, FULL }
    public enum MfaProfile { PLATFORM_STAFF, ORG_ADMIN, ORG_MEMBER, MERCHANT_OTP }
    public enum Assurance { AAL1, AAL2 }

    public record EnrolRequest(String userId, String loginName, String phone, String displayName, String passwordHash) {
        /** Enrolment without a credential import (the usual lifecycle path issues a set-up link). */
        public EnrolRequest(String userId, String loginName, String phone, String displayName) {
            this(userId, loginName, phone, displayName, null);
        }
    }
    public record Enrolment(String subject, String oneTimeLink, Instant expiresAt) { }

    public interface AuthProvider {
        String key();
        String issuer();
        Enrolment enrol(EnrolRequest request);
        Enrolment resetCredential(String subject, ResetScope scope);
        void disable(String subject, String reason);
        void enable(String subject);
        void revokeSessions(String subject);
        void updateLoginName(String subject, String loginName, String phone);
        void setMfaProfile(String subject, MfaProfile profile, boolean orgRequiresMfa);
        void unlink(String subject);
        Assurance assuranceOf(String acr, List<String> amr);
    }
}
