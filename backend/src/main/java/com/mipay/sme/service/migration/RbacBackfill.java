package com.mipay.sme.service.migration;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.kyc.UserPlatformFunction;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.domain.org.OrgRight;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.OrgRightRepository;
import com.mipay.sme.repository.UserPlatformFunctionRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.security.rbac.RbacMeshClient.RbacMeshException;
import com.mipay.sme.service.OrgProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RBAC backfill (cut-over P3.11): mirrors the core's legacy authorization rows into RBAC
 * so the shadow phase (P6) can compare like with like, and so enforcement can switch
 * without losing anyone. Admin-only and safe to re-run — an existing ACTIVE assignment
 * short-circuits to SKIPPED, grants are deduplicated by RBAC and reported either way.
 *
 * <ul>
 *   <li>{@code users.org_role} ADMIN/STAFF → {@code org_admin}/{@code org_staff} assigned on
 *       the user's org (scope own);</li>
 *   <li>{@code user_platform_functions} → the matching platform roles (scope all, tenant =
 *       PLATFORM); {@code TPTS_OPS} → platform_ops_reviewer + platform_operations;
 *       {@code MINDLABS_ADMIN} → platform_user_admin + platform_engineer (D9);</li>
 *   <li>{@code org_rights}: DIRECT → an own grant for {@code org.onboarding:create} limited
 *       to the child type; CEILING → a delegable subtree grant for the same cell.</li>
 * </ul>
 *
 * <p>RBAC applies SoD and ceilings even to system writes, so a refusal shows up as a FAILED
 * row here — which is exactly the static-conflict report the cut-over asks for. The run also
 * reports legacy inconsistencies RBAC cannot express: a platform member holding an org role,
 * and a user whose {@code role} disagrees with their org's type.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RbacBackfill {

    private final UserRepository users;
    private final OrganizationRepository orgs;
    private final OrgRightRepository rights;
    private final UserPlatformFunctionRepository platformFunctions;
    private final RbacMeshClient rbac;
    private final OrgProvisioningService provisioning;

    public record Row(String subject, String role, String tenant, String resolution, String note) { }

    public record Report(int assignmentsWritten, int grantsWritten, List<Row> rows,
                         List<String> conflicts, boolean errors) { }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Report preview() {
        return scan(true);
    }

    public Report run() {
        return scan(false);
    }

    private Report scan(boolean dry) {
        List<Row> rows = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int assignments = 0, grants = 0;
        boolean errors = false;

        if (!rbac.configured()) {
            throw new IllegalStateException("RBAC is not configured (APP_RBAC_REGISTRY_URL)");
        }
        String platformTenant = provisioning.ensurePlatformRoot().getId();

        // 1. org membership: org_role becomes org_admin / org_staff on the user's org
        Map<String, Map<String, Object>> orgTypeByUser = new HashMap<>();
        for (User u : users.findAll().stream().filter(u -> u.getOrgId() != null).toList()) {
            Organization org = orgs.findById(u.getOrgId()).orElse(null);
            if (org == null) {
                rows.add(new Row(u.getId(), "org_member", u.getOrgId(), "FAILED", "user org does not exist"));
                errors = true;
                continue;
            }
            String mismatch = typeMismatch(u, org);
            if (mismatch != null) conflicts.add(mismatch);

            if (Enums.OrgType.PLATFORM.equals(org.getOrgType())) {
                // platform members' authority comes from platform roles; a stray org_role is reported
                if (u.getOrgRole() != null) conflicts.add(
                        "platform member " + u.getId() + " holds org_role " + u.getOrgRole() + " (dropped)");
                rows.add(new Row(u.getId(), String.valueOf(u.getOrgRole()), platformTenant,
                        "SKIPPED", "platform member: platform roles only"));
            } else if (u.getOrgRole() == null) {
                rows.add(new Row(u.getId(), "org_member", org.getId(), "SKIPPED", "no org_role"));
            } else {
                String role = u.getOrgRole() == Enums.OrgMemberRole.ADMIN ? "org_admin" : "org_staff";
                if (!dry && assign(rows, u.getId(), role, org.getId(), "own")) assignments++;
                else if (dry) rows.add(new Row(u.getId(), role, org.getId(), "WOULD_ASSIGN", null));
            }
        }

        // 2. platform functions -> platform roles (scope all)
        Map<String, String> functionRole = Map.of(
                UserPlatformFunction.OPS_REVIEWER, "platform_ops_reviewer",
                UserPlatformFunction.COMPLIANCE_OFFICER, "platform_compliance_officer",
                UserPlatformFunction.MLRO, "platform_mlro",
                UserPlatformFunction.SENIOR_APPROVER, "platform_senior_approver");
        for (UserPlatformFunction f : platformFunctions.findAll()) {
            String role = functionRole.get(f.getFunction());
            if (role == null) {
                conflicts.add("unknown platform function " + f.getFunction() + " on user " + f.getUserId());
                continue;
            }
            if (!dry && assign(rows, f.getUserId(), role, platformTenant, "all")) assignments++;
            else if (dry) rows.add(new Row(f.getUserId(), role, platformTenant, "WOULD_ASSIGN", "from " + f.getFunction()));
        }

        // 3. legacy platformUserRole enum values -> D9 platform roles
        for (User u : users.findAll().stream()
                .filter(x -> x.getRole() == Enums.UserRole.TPTS_OPS || x.getRole() == Enums.UserRole.MINDLABS_ADMIN)
                .toList()) {
            // the D9 roles, plus the legacy-parity roles (grid.yaml) so enforcement changes no
            // one's access; sandbox_tester covers the simulation screens and is refused in production
            List<String> roles = u.getRole() == Enums.UserRole.TPTS_OPS
                    ? List.of("platform_ops_reviewer", "platform_operations", "legacy_tpts_ops", "legacy_tpts_ops_admin")
                    : List.of("platform_user_admin", "platform_engineer", "legacy_mindlabs_admin",
                            "legacy_mindlabs_review", "sandbox_tester");
            for (String role : roles) {
                if (!dry && assign(rows, u.getId(), role, platformTenant, "all")) assignments++;
                else if (dry) rows.add(new Row(u.getId(), role, platformTenant, "WOULD_ASSIGN", "from " + u.getRole()));
            }
        }

        // 4. org_rights -> org.onboarding:create grants (DIRECT own, CEILING delegable subtree)
        for (OrgRight right : rights.findAll()) {
            String scope = right.getKind() == Enums.OrgRightKind.DIRECT ? "own" : "subtree";
            boolean delegate = right.getKind() == Enums.OrgRightKind.CEILING;
            String note = right.getKind().name().toLowerCase() + " onboarding of " + right.getTargetType();
            if (dry) {
                rows.add(new Row(right.getOrgId(), "org.onboarding:create:" + right.getTargetType(),
                        right.getOrgId(), "WOULD_GRANT", note));
                continue;
            }
            try {
                rbac.grant(right.getOrgId(), "org.onboarding:create",
                        List.of(right.getTargetType().name()), scope, delegate);
                grants++;
                rows.add(new Row(right.getOrgId(), "org.onboarding:create:" + right.getTargetType(),
                        right.getOrgId(), "GRANTED", note));
            } catch (RbacMeshException e) {
                errors = true;
                rows.add(new Row(right.getOrgId(), "org.onboarding:create:" + right.getTargetType(),
                        right.getOrgId(), "FAILED", e.code + ": " + trim(e.getMessage())));
            }
        }

        for (String conflict : conflicts) log.warn("[rbac-backfill] conflict: {}", conflict);
        log.info("[rbac-backfill] dry={} assignments={} grants={} conflicts={}", dry, assignments, grants, conflicts.size());
        return new Report(assignments, grants, rows, conflicts, errors);
    }

    /** Writes one assignment unless an ACTIVE one already matches (re-run safety). */
    private boolean assign(List<Row> rows, String subject, String role, String tenant, String scope) {
        try {
            boolean exists = rbac.assignmentsFor(subject).stream().anyMatch(a ->
                    role.equals(String.valueOf(a.get("role"))) && tenant.equals(String.valueOf(a.get("tenant")))
                            && scope.equals(String.valueOf(a.get("scope"))) && "ACTIVE".equals(String.valueOf(a.get("status"))));
            if (exists) {
                rows.add(new Row(subject, role, tenant, "SKIPPED", "active assignment already exists"));
                return false;
            }
            rbac.assignSystem(subject, role, tenant, scope, "legacy authorization backfill");
            rows.add(new Row(subject, role, tenant, "ASSIGNED", null));
            return true;
        } catch (RbacMeshException e) {
            rows.add(new Row(subject, role, tenant, "FAILED",
                    e.code + (e.code.contains("SOD") ? " (static SoD conflict)" : ": " + trim(e.getMessage()))));
            return false;
        }
    }

    /** A user whose legacy role says one org type while sitting in an org of another. */
    private static String typeMismatch(User u, Organization org) {
        String role = u.getRole() == null ? "" : u.getRole().name();
        String orgType = String.valueOf(org.getOrgType());
        boolean business = role.equals("MANUFACTURER") || role.equals("DISTRIBUTOR")
                || role.equals("SUPPLIER") || role.equals("MERCHANT") || role.equals("LENDER");
        if (business && !orgType.equalsIgnoreCase(role)) {
            return "user " + u.getId() + " role " + role + " sits in a " + orgType + " org";
        }
        return null;
    }

    private static String trim(String message) {
        if (message == null) return "";
        return message.length() <= 120 ? message : message.substring(0, 120);
    }
}
