package com.mipay.sme.security.rbac;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.org.Organization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gives a newly live organization's administrator the starter role for its kind
 * (grid seed v1, now database-owned: onboard organizations below it and manage its users,
 * nothing else). Without it the administrator logs in to an empty portal and cannot even
 * open User management to build roles for their people.
 *
 * <p>Never fatal: a failure is logged at error level and the org still goes live; an admin
 * of the parent org (or the platform) can give the role from User management.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StarterRoleService {

    private final RbacMeshClient rbac;

    /** The starter role (grid seed v1, now database-owned) for an org type, or null when the type has none (PLATFORM). */
    public static String starterRole(Enums.OrgType type) {
        if (type == null) return null;
        return switch (type) {
            case MANUFACTURER -> "manufacturer_role";
            case SUPPLIER -> "supplier_role";
            case DISTRIBUTOR -> "reseller_role";
            case MERCHANT, LENDER -> "admin_role";
            case PLATFORM -> null;   // root admins hold organization_root (RbacBackfill)
        };
    }

    public void grantAtGoLive(Organization org, String adminUid) {
        String role = starterRole(org.getOrgType());
        if (role == null || adminUid == null || !rbac.configured()) return;
        try {
            boolean has = rbac.assignmentsFor(adminUid).stream().anyMatch(a ->
                    role.equals(String.valueOf(a.get("role"))) && org.getId().equals(String.valueOf(a.get("tenant")))
                            && "ACTIVE".equalsIgnoreCase(String.valueOf(a.get("status"))));
            if (has) return;
            rbac.assignSystem(adminUid, role, org.getId(), "own", "starter role at go-live");
            log.info("[go-live] {} given {} in {}", adminUid, role, org.getId());
        } catch (Exception e) {
            log.error("[go-live] starter role {} FAILED for {} in {} - give it from User management: {}",
                    role, adminUid, org.getId(), e.getMessage());
        }
    }
}
