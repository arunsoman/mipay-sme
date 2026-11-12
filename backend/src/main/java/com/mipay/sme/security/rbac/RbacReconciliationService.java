package com.mipay.sme.security.rbac;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only cutover gate for the core org_role and RBAC assignment sources of truth. */
@Service
@RequiredArgsConstructor
public class RbacReconciliationService {
    private static final Set<String> CORE_ROLES = Set.of("org_admin", "org_staff", "kyc_applicant");
    private static final int RBAC_LIST_LIMIT = 500;

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final RbacMeshClient rbac;

    public record Mismatch(String userId, String username, String status, String coreRole,
                           String expectedRole, List<String> activeRbacRoles, String reason) { }

    public record Report(String orgId, String lifecycle, int users, int activeCoreRoleAssignments,
                         int mismatches, List<Mismatch> rows) { }

    public Report report(String orgId) {
        Organization org = orgs.findById(orgId).orElseThrow(() -> new NotFoundException("Organization not found"));
        if (org.getOrgType() == Enums.OrgType.PLATFORM) {
            throw new ConflictException("PLATFORM_ROLE_MAPPING_REQUIRED", "Platform roles need a separate legacy-to-RBAC mapping");
        }
        if (org.getLifecycle() != Enums.OrgStatus.ACTIVE && org.getLifecycle() != Enums.OrgStatus.PENDING) {
            throw new ConflictException("ORG_NOT_ACTIVE", "Reconciliation is available for active or pending organizations");
        }
        List<Map<String, Object>> assignments = rbac.assignments(orgId);
        if (assignments.size() >= RBAC_LIST_LIMIT) {
            throw new ConflictException("RBAC_ASSIGNMENTS_TRUNCATED", "RBAC returned its 500-row limit; a complete reconciliation is unavailable");
        }
        return compare(orgId, org.getLifecycle(), users.findByOrgId(orgId), assignments);
    }

    static Report compare(String orgId, Enums.OrgStatus lifecycle, List<User> members,
                          List<Map<String, Object>> assignments) {
        Map<String, List<String>> active = new HashMap<>();
        int activeCount = 0;
        for (Map<String, Object> assignment : assignments) {
            String role = String.valueOf(assignment.get("role"));
            if (!CORE_ROLES.contains(role) || !"ACTIVE".equals(assignment.get("status"))
                    || !"own".equals(assignment.get("scope")) || !orgId.equals(assignment.get("tenant"))) continue;
            String subject = String.valueOf(assignment.get("subject"));
            active.computeIfAbsent(subject, ignored -> new ArrayList<>()).add(role);
            activeCount++;
        }

        List<Mismatch> mismatches = new ArrayList<>();
        for (User member : members) {
            List<String> actual = active.remove(member.getId());
            if (actual == null) actual = List.of();
            else actual = actual.stream().sorted().toList();
            String expected = expectedRole(member, lifecycle);
            List<String> expectedList = expected == null ? List.of() : List.of(expected);
            String reason = member.getStatus() != Enums.UserStatus.ARCHIVED && member.getOrgRole() == null
                    ? "CORE_ROLE_UNSET" : !actual.equals(expectedList) ? "ROLE_MISMATCH" : null;
            if (reason != null) mismatches.add(new Mismatch(member.getId(), member.getUsername(),
                    member.getStatus() == null ? null : member.getStatus().name(),
                    member.getOrgRole() == null ? null : member.getOrgRole().name(), expected, actual, reason));
        }
        for (Map.Entry<String, List<String>> orphan : active.entrySet()) {
            mismatches.add(new Mismatch(orphan.getKey(), null, null, null, null,
                    orphan.getValue().stream().sorted().toList(), "ORPHAN_ASSIGNMENT"));
        }
        mismatches.sort(Comparator.comparing(Mismatch::userId));
        return new Report(orgId, lifecycle.name(), members.size(), activeCount, mismatches.size(), mismatches);
    }

    private static String expectedRole(User member, Enums.OrgStatus lifecycle) {
        if (member.getStatus() == Enums.UserStatus.ARCHIVED || member.getOrgRole() == null) return null;
        return switch (member.getOrgRole()) {
            case ADMIN -> lifecycle == Enums.OrgStatus.PENDING ? "kyc_applicant" : "org_admin";
            case STAFF -> "org_staff";
        };
    }
}
