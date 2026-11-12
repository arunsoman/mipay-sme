package com.mipay.sme.security.rbac;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RbacReconciliationServiceTest {

    private static User user(String id, Enums.UserStatus status, Enums.OrgMemberRole role) {
        User u = new User();
        u.setId(id);
        u.setUsername(id);
        u.setStatus(status);
        u.setOrgRole(role);
        return u;
    }

    private static Map<String, Object> assignment(String subject, String role) {
        return Map.of("subject", subject, "role", role, "status", "ACTIVE", "scope", "own", "tenant", "org-1");
    }

    @Test
    void reportsMissingStaleUnmappedAndOrphanAdminAssignments() {
        var members = List.of(user("admin", Enums.UserStatus.ACTIVE, Enums.OrgMemberRole.ADMIN),
                user("staff", Enums.UserStatus.ACTIVE, Enums.OrgMemberRole.STAFF),
                user("archived", Enums.UserStatus.ARCHIVED, Enums.OrgMemberRole.ADMIN),
                user("unmapped", Enums.UserStatus.ACTIVE, null));
        var assignments = List.of(assignment("admin", "org_admin"), assignment("archived", "org_admin"),
                assignment("orphan", "org_staff"));

        var report = RbacReconciliationService.compare("org-1", Enums.OrgStatus.ACTIVE, members, assignments);
        assertEquals(4, report.users());
        assertEquals(3, report.activeCoreRoleAssignments());
        assertEquals(4, report.mismatches());
        assertEquals(List.of("archived", "orphan", "staff", "unmapped"),
                report.rows().stream().map(RbacReconciliationService.Mismatch::userId).toList());
        assertEquals("ORPHAN_ASSIGNMENT", report.rows().get(1).reason());
        assertEquals("org_staff", report.rows().get(2).expectedRole());
        assertEquals("CORE_ROLE_UNSET", report.rows().get(3).reason());
    }

    @Test
    void pendingApplicantUsesKycRoleUntilGoLive() {
        var report = RbacReconciliationService.compare("org-1", Enums.OrgStatus.PENDING,
                List.of(user("admin", Enums.UserStatus.PENDING, Enums.OrgMemberRole.ADMIN)),
                List.of(assignment("admin", "kyc_applicant")));
        assertEquals(0, report.mismatches());
    }
}
