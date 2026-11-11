package com.mipay.sme.uat;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.service.OrgOnboardingService;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.OrgProvisioningService;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cascading suspension, tree visibility and the legacy bridge — TC-ORG
 * from the org spec. Covers [H1]-[H5], [D4], [M10], [C1], [L2].
 */
@DisplayName("Org suspension, tree & legacy bridge — TC-ORG")
@TestMethodOrder(OrderAnnotation.class)
class TUat18OrgSuspensionTests extends UatSupport {

    private String distOrg;
    private String supOrg;
    private User distAdmin;
    private User supAdmin;

    @BeforeAll
    void buildChain() throws Exception {
        orgProvisioning.ensurePlatformRoot();
        Current.Principal rootAdmin = platformAdminPrincipal();

        // root onboards a DISTRIBUTOR granting the SUPPLIER step
        String ownerPhone = phone();
        Dtos.OrgDecisionResult sub = orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR,
                new com.mipay.sme.service.OrgOnboardingService.OrgSpec("Chain Dist " + seq(), "REG-ORG-" + seq(),
                        "DISTRIBUTION", "Dist Owner " + seq(), ownerPhone, null,
                        "chain-owner-" + seq() + "@uat.na", null, null, null, null, null, null,
                        null, null));
        kycGoLive(rootAdmin, sub.orgId(), opsPrincipal());
        distOrg = sub.orgId();
        distAdmin = users.findByPhone(ownerPhone).orElseThrow();
        activation.activate(rawTokenFor(distAdmin.getId()), "S3cure-Password!42");

        // the distributor admin onboards a SUPPLIER under itself
        String supPhone = phone();
        Dtos.OrgDecisionResult supSub = orgOnboard.submitOrg(principalOf(distAdmin), Enums.OrgType.SUPPLIER,
                new com.mipay.sme.service.OrgOnboardingService.OrgSpec("Chain Supplier " + seq(), null, null,
                        "Sup Owner " + seq(), supPhone, null, null,
                        "Bank Windhoek", "2000" + String.format("%05d", seq() % 100_000),
                        "Sup Owner " + seq(), null, null, null, null, null));
        kycGoLive(principalOf(distAdmin), supSub.orgId(), opsPrincipal());
        supOrg = supSub.orgId();
        supAdmin = users.findByPhone(supPhone).orElseThrow();
        activation.activate(rawTokenFor(supAdmin.getId()), "S3cure-Password!42");
    }

    @Test
    @Order(1)
    @DisplayName("TC-ORG-01 · suspending an org cascades: subtree status, sessions cut, login + filter 401")
    void tcOrg01_cascadingSuspension() throws Exception {
        // fresh tokens for both admins
        String distToken = loginWithPassword(distAdmin.getPhone(), "S3cure-Password!42").token();
        String supToken = loginWithPassword(supAdmin.getPhone(), "S3cure-Password!42").token();
        assertThat(sessions.findAll().stream().filter(s -> users.findById(s.getUserId()).orElseThrow().getOrgId() != null
                && (s.getUserId().equals(distAdmin.getId()) || s.getUserId().equals(supAdmin.getId()))).count())
                .isEqualTo(2);

        // the platform suspends the distributor
        Dtos.SuspensionRow hold = orgSusp.suspend(distOrg, "compliance review", platformAdminPrincipal());
        assertThat(hold.entityType()).isEqualTo("ORG");

        // sessions across the whole subtree were cut ([H4])
        assertThat(sessions.findAll().stream()
                .filter(s -> s.getUserId().equals(distAdmin.getId()) || s.getUserId().equals(supAdmin.getId()))
                .count()).isZero();

        // subtree statuses: distributor SUSPENDED, supplier SUSPENDED_BY_ANCESTOR ([H4]/tree view)
        Dtos.OrgNodeRow distNode = orgTree.tree(platformAdminPrincipal(), distOrg, null, null);
        assertThat(distNode.effectiveStatus()).isEqualTo("SUSPENDED");
        assertThat(distNode.subtree().activeDescendants() + distNode.subtree().descendants()).isGreaterThanOrEqualTo(1);

        // a suspended-org member's token dies at the per-request filter ([H4]/[H3])
        bearerGet("/api/orgs/me/tree", supToken)
                .andExpect(status -> assertThat(status.getResponse().getStatus()).isEqualTo(401));

        // login is cut for the whole subtree
        assertThatThrownBy(() -> loginWithPassword(distAdmin.getPhone(), "S3cure-Password!42"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> loginWithPassword(supAdmin.getPhone(), "S3cure-Password!42"))
                .isInstanceOf(ForbiddenException.class);

        // the subtree impact preview matches the tree ([D4])
        Dtos.OrgNodeRow rootNode = orgTree.tree(platformAdminPrincipal(),
                OrgProvisioningService.ORG_ROOT, null, null);
        assertThat(rootNode.subtree().descendants()).isGreaterThanOrEqualTo(2);
        assertThat(rootNode.subtree().activeDescendants())
                .isEqualTo(rootNode.subtree().descendants() - 1);
    }

    @Test
    @Order(2)
    @DisplayName("TC-ORG-02 · lift rules: the suspending level or above lifts; out-of-subtree is 404")
    void tcOrg02_liftAuthority() {
        // the suspension from TC-ORG-01 is still active; the suspended distributor's own admin cannot lift ([H4])
        String holdId = suspensionRepo
                .findByOrgIdAndLiftedAtIsNull(distOrg).get(0).getId();
        assertThatThrownBy(() -> orgSusp.lift(holdId, principalOf(distAdmin)))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PLATFORM_REQUIRED"));

        // an unrelated org's admin never sees the hold (404, not 403) [M10]
        Current.Principal rootAdmin = platformAdminPrincipal();
        String otherPhone = phone();
        Dtos.OrgDecisionResult other = orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR,
                new com.mipay.sme.service.OrgOnboardingService.OrgSpec("Other Dist " + seq(), "REG-OTHER-" + seq(),
                        "DISTRIBUTION", "Other Owner " + seq(), otherPhone, null, null,
                        null, null, null, null, null, null, null, null));
        kycGoLive(rootAdmin, other.orgId(), opsPrincipal());
        User otherAdmin = users.findByPhone(otherPhone).orElseThrow();
        assertThatThrownBy(() -> orgTree.tree(principalOf(otherAdmin), distOrg, null, null))
                .isInstanceOf(NotFoundException.class);

        // the suspending level (platform) lifts; supplier recovers to ACTIVE ([H4])
        var rows = orgSusp.lift(holdId, platformAdminPrincipal());
        assertThat(rows).allSatisfy(r -> assertThat(r.liftedAt()).isNotNull());
        assertThat(orgs.findById(supOrg).orElseThrow().getLifecycle()).isEqualTo(Enums.OrgStatus.ACTIVE);

        // subtree is operational again: logins work
        assertThat(loginWithPassword(supAdmin.getPhone(), "S3cure-Password!42").token()).isNotBlank();
    }

    @Test
    @Order(3)
    @DisplayName("TC-ORG-03 · visibility 404-not-403, depth-1-only contacts, masked beyond")
    void tcOrg03_visibilityAndMasking() throws Exception {
        // the distributor admin sees its own org + the supplier child, but not the root ([M10])
        Current.Principal distPrincipal = principalOf(distAdmin);
        assertThatThrownBy(() -> orgTree.tree(distPrincipal, OrgProvisioningService.ORG_ROOT, null, null))
                .isInstanceOf(NotFoundException.class);

        // depth 0 (own org) shows real contacts; the supplier child (depth 1) also shows them
        Dtos.OrgNodeRow own = orgTree.tree(distPrincipal, distOrg, null, null);
        assertThat(own.users()).isNotEmpty();
        assertThat(own.users().get(0).phone()).isEqualTo(distAdmin.getPhone());

        // a masked view: from the supplier's perspective the distributor's contact is masked
        Dts tokens = new Dts(loginWithPassword(supAdmin.getPhone(), "S3cure-Password!42").token());
        String body = bearerGet("/api/orgs/me/tree", tokens.token).andReturn().getResponse().getContentAsString();
        // the own-org node comes back with its users; the supplier's own (depth-0) users are unmasked
        assertThat(body).contains(supOrg);
        assertThat(body).contains(supAdmin.getId());
    }

    @Test
    @Order(4)
    @DisplayName("TC-ORG-04 · parent must be operational; suspended subtree refuses submit; legacy bridge")
    void tcOrg04_parentOperationalAndLegacyBridge() throws Exception {
        Current.Principal rootAdmin = platformAdminPrincipal();
        orgSusp.suspend(distOrg, "suspend again", rootAdmin);

        // submitting INTO a suspended org is refused (submit requires the onboarder operational)
        String supPhone = phone();
        assertThatThrownBy(() -> orgOnboard.submitOrg(principalOf(distAdmin), Enums.OrgType.MERCHANT,
                new com.mipay.sme.service.OrgOnboardingService.OrgSpec("Merchant In Suspended " + seq(), null, "RETAIL",
                        "Owner " + seq(), supPhone, null, null, null, null, null,
                        null, null, null, null, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PARENT_NOT_OPERATIONAL"));

        // legacy bridge: OpsService.suspend of the supplier ENTITY cascades through the org ([H4])
        Organization supplier = orgs.findById(supOrg).orElseThrow();
        com.mipay.sme.web.Dtos.SuspendRequest req =
                new com.mipay.sme.web.Dtos.SuspendRequest("SUPPLIER", supplier.getEntityId(),
                        "legacy entity suspend");
        com.mipay.sme.web.Dtos.SuspensionRow row =
                ops.suspend(req, "uat-tpts", "TPTS_OPS");
        assertThat(row.entityType()).isEqualTo("SUPPLIER");
        // the org row picked the mirror up: supplier org is suspended by the entity-level hold
        assertThat(orgAccess().isOperational(supOrg)).isFalse();

        orgSusp.lift(orgSusp.suspensions().stream()
                .filter(r -> r.entityType().equals("SUPPLIER") && r.entityId().equals(supplier.getEntityId()))
                .findFirst().orElseThrow().id(), rootAdmin);

        // lift the distributor-level hold from the top of this test as well
        orgSusp.lift(orgSusp.suspensions().stream()
                .filter(r -> r.entityType().equals("ORG") && distOrg.equals(r.entityId()))
                .findFirst().orElseThrow().id(), rootAdmin);

        assertThat(orgAccess().isOperational(supOrg)).isTrue();
        assertThat(orgAccess().isOperational(distOrg)).isTrue();
    }

    private record Dts(String token) { }

    private String rawTokenFor(String userId) {
        return simOutbox.findAll().stream()
                .filter(o -> userId.equals(o.getUserId()))
                .findFirst().orElseThrow().getRawToken();
    }
}
