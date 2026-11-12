package com.mipay.sme.uat;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.OrgOnboardingService;
import com.mipay.sme.service.OrgProvisioningService;
import com.mipay.sme.service.OrgRoleCeilingService;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extended RBAC, the core side (docs/plans/extended-rbac.md ER1, ER3): the onboarder chooses
 * the roles a new org may use and hand down; children inherit; narrowing cascades. Runs
 * without RBAC (H2), so the role catalogue is app.rbac.org-roles.
 */
@DisplayName("Extended RBAC · role ceilings per org")
class TUat20RoleCeilingTests extends UatSupport {

    @Autowired OrgRoleCeilingService ceilings;

    @BeforeAll
    void root() {
        orgProvisioning.ensurePlatformRoot();
    }

    private OrgOnboardingService.OrgSpec dist(List<String> roles, List<String> delegable) {
        return new OrgOnboardingService.OrgSpec("Ceiling Dist " + seq(), "REG-CEIL-" + seq(), "DISTRIBUTION",
                "Owner " + seq(), phone(), null, "ceil-" + seq() + "@uat.na", null, null, null, null, null, null,
                java.util.Set.of(Enums.OrgType.MERCHANT), java.util.Set.of(Enums.OrgType.MERCHANT), roles, delegable);
    }

    private OrgOnboardingService.OrgSpec merchant(List<String> roles, List<String> delegable) {
        return new OrgOnboardingService.OrgSpec("Ceiling Shop " + seq(), "REG-SHOP-" + seq(), "RETAIL",
                "Shop Owner " + seq(), phone(), null, "shop-" + seq() + "@uat.na", null, null, null, null, null, null,
                null, null, roles, delegable);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> view, String key) {
        return (List<String>) view.get(key);
    }

    @Test
    @DisplayName("onboarding sets the ceiling; children inherit; the onboarder's hand-down set bounds them; narrowing cascades")
    void ceilingsThroughOnboarding() {
        Current.Principal root = platformAdminPrincipal();

        // unknown roles are refused at submit
        assertThatThrownBy(() -> orgOnboard.submitOrg(root, Enums.OrgType.DISTRIBUTOR, dist(List.of("platform_auditor"), List.of())))
                .isInstanceOfSatisfying(SimException.class, e -> assertThat(e.getCode()).isEqualTo("ROLES_INVALID"));
        // delegable must be within its own roles
        assertThatThrownBy(() -> orgOnboard.submitOrg(root, Enums.OrgType.DISTRIBUTOR, dist(List.of("org_viewer"), List.of("org_staff"))))
                .isInstanceOfSatisfying(SimException.class, e -> assertThat(e.getCode()).isEqualTo("ROLES_INVALID"));

        // the platform (unrestricted) gives Namib staff + viewer, hand-down viewer only
        Dtos.OrgDecisionResult d = orgOnboard.submitOrg(root, Enums.OrgType.DISTRIBUTOR,
                dist(List.of("org_staff", "org_viewer"), List.of("org_viewer")));
        Map<String, Object> dv = ceilings.view(d.orgId());
        assertThat(dv.get("explicit")).isEqualTo(true);
        assertThat(list(dv, "roles")).containsExactlyInAnyOrder("org_staff", "org_viewer", "kyc_applicant", "org_admin");
        assertThat(list(dv, "delegableRoles")).containsExactly("org_viewer");

        User distAdmin = users.findAll().stream().filter(u -> d.orgId().equals(u.getOrgId())).findFirst().orElseThrow();
        kycGoLive(principalOf(distAdmin), d.orgId(), opsPrincipal());
        Current.Principal dp = principalOf(distAdmin);

        // what Namib may hand down: its delegable set plus the baseline
        assertThat(ceilings.handDown(d.orgId())).containsExactly("org_viewer");

        // beyond the onboarder's hand-down set: refused
        assertThatThrownBy(() -> orgOnboard.submitOrg(dp, Enums.OrgType.MERCHANT, merchant(List.of("org_staff"), List.of())))
                .isInstanceOfSatisfying(SimException.class, e -> assertThat(e.getCode()).isEqualTo("ROLES_EXCEED_CEILING"));

        // within it: explicit; without a ceiling: inherited from Namib
        Dtos.OrgDecisionResult m1 = orgOnboard.submitOrg(dp, Enums.OrgType.MERCHANT, merchant(List.of("org_viewer"), List.of()));
        Dtos.OrgDecisionResult m2 = orgOnboard.submitOrg(dp, Enums.OrgType.MERCHANT, merchant(null, null));
        assertThat(list(ceilings.view(m1.orgId()), "roles")).containsExactlyInAnyOrder("org_viewer", "kyc_applicant", "org_admin");
        Map<String, Object> inherited = ceilings.view(m2.orgId());
        assertThat(inherited.get("explicit")).isEqualTo(false);
        assertThat(inherited.get("inheritedFrom")).isEqualTo(d.orgId());
        assertThat(list(inherited, "roles")).containsExactlyInAnyOrder("org_viewer", "kyc_applicant", "org_admin");

        // the platform narrows Namib's hand-down set: Oka's explicit ceiling is clamped to the baseline
        ceilings.set(d.orgId(), List.of("org_staff", "org_viewer"), List.of(), root.userId(), root.role());
        assertThat(list(ceilings.view(m1.orgId()), "roles")).containsExactlyInAnyOrder("kyc_applicant", "org_admin");
        assertThat(list(ceilings.view(m2.orgId()), "roles")).containsExactlyInAnyOrder("kyc_applicant", "org_admin");

        // the org-tree sync sends every explicit ceiling (null for inherit)
        Map<String, List<List<String>>> sent = ceilings.explicitCeilings();
        assertThat(sent).containsKeys(d.orgId(), m1.orgId()).doesNotContainKey(m2.orgId());

        // back to inherit: Namib is unrestricted again (nothing above it)
        Map<String, Object> cleared = ceilings.clear(d.orgId(), root.userId(), root.role());
        assertThat(cleared.get("unrestricted")).isEqualTo(true);
        assertThat(ceilings.effective(OrgProvisioningService.ORG_ROOT).unrestricted()).isTrue();
    }
}
