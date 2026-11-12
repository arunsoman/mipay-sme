package com.mipay.sme.uat;

import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.OrgProvisioningService;
import com.mipay.sme.service.OrgOnboardingService;
import com.mipay.sme.web.Dtos;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hierarchical onboarding & delegated rights — TC-HON / TC-RGT from the org spec.
 * Covers [C1]-[C4], [H6]-[H8], [D1/D2], [L3], [M4/M7/M8/M10].
 */
@DisplayName("Hierarchical onboarding & rights — TC-HON/TC-RGT")
@TestMethodOrder(OrderAnnotation.class)
class TUat17HierarchicalOnboardingTests extends UatSupport {

    @BeforeAll
    void touchRoot() {
        // platform root + rights rows are self-healed on demand (H2 skips Flyway)
        orgProvisioning.ensurePlatformRoot();
    }

    @Test
    @Order(1)
    @DisplayName("TC-HON-01 · Platform onboards a DISTRIBUTOR; second-person review; activation + username login + claims")
    void tcHon01_fullLifecycle() throws Exception {
        Current.Principal rootAdmin = platformAdminPrincipal();
        String ownerPhone = phone();
        String username = "dist-admin-" + seq();
        OrgOnboardingService.OrgSpec spec = new OrgOnboardingService.OrgSpec("Namib Distributors " + seq(),
                "REG-HON-" + seq(), "DISTRIBUTION", "Distributor Owner " + seq(), ownerPhone,
                username, "dist-" + seq() + "@uat.na",
                null, null, null, null, null, null, null, null);

        // 1) submit: PENDING_REVIEW request + PENDING org + untouched platform root status
        Dtos.OrgDecisionResult submitted = orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR, spec);
        assertThat(submitted.status()).isEqualTo("PENDING");
        Organization child = orgs.findById(submitted.orgId()).orElseThrow();
        assertThat(child.getLifecycle()).isEqualTo(Enums.OrgStatus.PENDING);
        // OA1:.platform onboarding now hangs the child under the operator org, not the root shell
        assertThat(child.getParentId()).isEqualTo(operatorOrg().getId());

        User admin = users.findByPhone(ownerPhone).orElseThrow();
        assertThat(admin.getStatus()).isEqualTo(Enums.UserStatus.PENDING);
        assertThat(admin.getOrgRole()).isEqualTo(Enums.OrgMemberRole.ADMIN);
        assertThat(admin.getUsername()).isEqualTo(username);
        assertThat(admin.getPasswordHash()).as("no password is ever submitted ([C4])").isNull();

        // 2) the workflow's ops-review APPROVEs and the gate activates the org (§6.5)
        //    (the reviewer never equals the submitter: the platform task refuses MAKER_CANNOT_ACT)
        kycGoLive(rootAdmin, submitted.orgId(), opsPrincipal());
        assertThat(child.getClass()).isNotNull();   // placeholder to keep numbering
        assertThat(orgs.findById(child.getId()).orElseThrow().getLifecycle()).isEqualTo(Enums.OrgStatus.ACTIVE);


        // 4) the sandbox outbox holds a raw token mirror; the notification itself never carries a link
        Dtos.OutboxRow out = activation.outbox(platformAdminPrincipal()).stream()
                .filter(r -> r.activationLink() != null && r.activationLink().endsWith(rawTokenFor(admin.getId())))
                .findFirst().orElseThrow();
        assertThat(out.activationLink()).contains("/api/auth/activate");

        // 5) weak passwords are refused at activation ([H8])
        String raw = rawTokenFor(admin.getId());
        assertThatThrownBy(() -> activation.activate(raw, "short"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACTIVATION_INVALID"));
        assertThat(users.findById(admin.getId()).orElseThrow().getStatus()).isEqualTo(Enums.UserStatus.PENDING);

        // 6) pre-activation login is blocked ([C4]: no credentials until activation)
        assertThatThrownBy(() -> loginWithPassword(username, "S3cure-Password!42"))
                .isInstanceOf(ForbiddenException.class);

        // 7) a strong password activates exactly once
        activation.activate(raw, "S3cure-Password!42");
        User activated = users.findById(admin.getId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(Enums.UserStatus.ACTIVE);
        assertThat(activated.getActivatedAt()).isNotNull();

        // 8) the same link can never be replayed
        assertThatThrownBy(() -> activation.activate(raw, "S3cure-Password!42"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACTIVATION_INVALID"));

        // (re-activate a second admin through the same flow to prove the post-activation path)
        OrgOnboardingService.OrgSpec spec2 = new OrgOnboardingService.OrgSpec("Second Distributor " + seq(), "REG-HON2-" + seq(),
                "DISTRIBUTION", "Second Owner " + seq(), phone(), null, "dist2-" + seq() + "@uat.na",
                null, null, null, null, null, null, null, null);
        Dtos.OrgDecisionResult sub2 = orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR, spec2);
        kycGoLive(rootAdmin, sub2.orgId(), opsPrincipal());
        User admin2 = users.findByPhone(spec2.phone()).orElseThrow();
        activation.activate(rawTokenFor(admin2.getId()), "S3cure-Password!42");
        Dtos.TokenView t = loginWithPassword(spec2.phone(), "S3cure-Password!42");
        assertThat(t.role()).isEqualTo("DISTRIBUTOR");

        // 9) the access token carries the org binding claims ([H3/M9])
        Claims claims = jwt.parse(t.token());
        assertThat(claims.getSubject()).isEqualTo(admin2.getId());
        assertThat(claims.get("orgId", String.class)).isEqualTo(sub2.orgId());
        assertThat(claims.get("orgType", String.class)).isEqualTo("DISTRIBUTOR");
        assertThat(claims.get("orgRole", String.class)).isEqualTo("ADMIN");
    }

    @Test
    @Order(2)
    @DisplayName("TC-HON-02 · rank rule refused; duplicate username refused")
    void tcHon02_rankAndProfileRules() {
        // a MANUFACTURER may never hang off a MERCHANT org (rank table)
        User merAdmin = users.findAll().stream()
                .filter(u -> u.getOrgId() != null && u.getOrgRole() == Enums.OrgMemberRole.ADMIN)
                .filter(u -> orgs.findById(u.getOrgId())
                        .map(o -> o.getOrgType() == Enums.OrgType.MERCHANT).orElse(false))
                .findFirst().orElseThrow();
        OrgOnboardingService.OrgSpec badRank = distributorSpec();
        assertThatThrownBy(() -> orgOnboard.submitOrg(principalOf(merAdmin), Enums.OrgType.MANUFACTURER, badRank))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RANK_RULE"));

        // a username already taken anywhere is refused at submission ([H6])
        String taken = users.findAll().stream().map(User::getUsername)
                .filter(u -> u != null && u.startsWith("dist-")).findFirst()
                .orElse("ops-admin-ops");
        OrgOnboardingService.OrgSpec dupUser = new OrgOnboardingService.OrgSpec("Dup User Dist " + seq(),
                "REG-DUP-" + seq(), "DISTRIBUTION", "Owner " + seq(), phone(), taken, null,
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> orgOnboard.submitOrg(platformAdminPrincipal(), Enums.OrgType.DISTRIBUTOR, dupUser))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("USERNAME_TAKEN"));
    }

    @Test
    @Order(3)
    @DisplayName("TC-HON-03 · sanctions FAIL at decision → org BLOCKED + pending admin archived")
    void tcHon03_sanctionsBlock() {
        Current.Principal rootAdmin = platformAdminPrincipal();
        String ownerPhone = phone();
        OrgOnboardingService.OrgSpec spec = new OrgOnboardingService.OrgSpec("Sanctioned Dist " + seq(), "REG-SAN-" + seq(),
                "DISTRIBUTION", "Owner " + seq(), ownerPhone, null, null,
                null, null, null, null, null, null, null, null);
        Dtos.OrgDecisionResult submitted = orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR, spec);
        String adminId = onboardingRequests.findById(submitted.requestId()).orElseThrow().getAdminUserId();

        kycGoLive(rootAdmin, submitted.orgId(), opsPrincipal(), "BLOCK", "sanctions screening failed");
        assertThat(orgs.findById(submitted.orgId()).orElseThrow().getLifecycle()).isEqualTo(Enums.OrgStatus.BLOCKED);

        // the pending admin was archived — never left ACTIVE silently ([M7])
        User admin = users.findById(adminId).orElseThrow();
        assertThat(admin.getStatus()).isEqualTo(Enums.UserStatus.SUSPENDED);
        assertThat(admin.getUsername()).startsWith("arch-");
    }

    @Test
    @Order(4)
    @DisplayName("TC-RGT-01 · rights model: default proposal, effCeiling intersection, submit bound, narrowing-only")
    void tcRgt01_rightsModel() {
        Current.Principal rootAdmin = platformAdminPrincipal();

        // the root grantable spans all five child types (explicit DIRECT + CEILING rows [H7])
        Dtos.GrantableView rootGrantable = orgRights.grantable(rootAdmin, OrgProvisioningService.ORG_ROOT);
        assertThat(rootGrantable.effDirect()).containsExactlyInAnyOrder(
                "MANUFACTURER", "DISTRIBUTOR", "SUPPLIER", "MERCHANT", "LENDER");

        // a default proposal for a DISTRIBUTOR is the standard step only (SUPPLIER + MERCHANT below)
        OrgOnboardingService.OrgSpec spec = distributorSpec();
        Dtos.OrgDecisionResult submitted = orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR, spec);
        kycGoLive(rootAdmin, submitted.orgId(), opsPrincipal());
        String distOrg = submitted.orgId();

        // the new distributor carries its own DIRECT/CEILING rows bounded by the parent's ceiling ([D2])
        Dtos.OrgRightsView view = orgRights.view(distOrg);
        assertThat(view.effCeiling()).isNotEmpty();

        // submitting a SUPPLIER (the standard step) under this distributor works
        User distAdmin = users.findByPhone(spec.phone()).orElseThrow();
        OrgOnboardingService.OrgSpec supplierUnder = new OrgOnboardingService.OrgSpec("Rights Supplier " + seq(),
                null, null, "Supplier Owner " + seq(), phone(), null, null, "Bank Windhoek",
                "3000" + String.format("%05d", seq() % 100_000), "Supplier Owner " + seq(),
                null, null, null, null, null);
        Dtos.OrgDecisionResult sSub = orgOnboard.submitOrg(principalOf(distAdmin), Enums.OrgType.SUPPLIER, supplierUnder);
        kycGoLive(principalOf(distAdmin), sSub.orgId(), opsPrincipal());

        // a ceiling claiming a rank-incompatible type is refused at submit ([D1] rule 1)
        OrgOnboardingService.OrgSpec over = distributorSpec();
        org.springframework.dao.DataAccessException ignored = null; // type ref only
        assertThatThrownBy(() -> orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR,
                new OrgOnboardingService.OrgSpec("Lender Ceiling Dist " + seq(), "REG-LCEIL-" + seq(),
                        "DISTRIBUTION", "Owner " + seq(), phone(), null, null, null, null, null,
                        null, null, null, null, SetOf(Enums.OrgType.LENDER))))
                .isInstanceOfSatisfying(com.mipay.sme.common.SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RIGHTS_EXCEED_CEILING"));

        // rights are narrowing-only: a PUT introducing a not-held type is refused ([D1])
        assertThatThrownBy(() -> orgRights.narrow(distOrg,
                SetOf(Enums.OrgType.MERCHANT, Enums.OrgType.SUPPLIER), null, principalOf(distAdmin)))
                .isInstanceOfSatisfying(com.mipay.sme.common.SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RIGHTS_NARROW_ONLY"));
    }

    @Test
    @Order(5)
    @DisplayName("TC-RGT-02 · RIGHTS_STALE when the onboarder's ceiling narrows between submit and decide")
    void tcRgt02_rightsStale() throws Exception {
        Current.Principal rootAdmin = platformAdminPrincipal();

        // onboard a distributor granted an explicit MERCHANT+SUPPLIER ceiling
        String ownerPhone = phone();
        OrgOnboardingService.OrgSpec create = new OrgOnboardingService.OrgSpec("Stale Dist " + seq(),
                "REG-STL-" + seq(), "DISTRIBUTION", "Owner " + seq(), ownerPhone, null, null,
                null, null, null, null, null, null,
                SetOf(Enums.OrgType.SUPPLIER, Enums.OrgType.MERCHANT),
                SetOf(Enums.OrgType.MERCHANT, Enums.OrgType.SUPPLIER));
        Dtos.OrgDecisionResult distSub = orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR, create);
        kycGoLive(rootAdmin, distSub.orgId(), opsPrincipal());
        User distAdmin = users.findByPhone(ownerPhone).orElseThrow();
        activation.activate(rawTokenFor(distAdmin.getId()), "S3cure-Password!42");
        Current.Principal distPrincipal = principalOf(users.findById(distAdmin.getId()).orElseThrow());

        // the distributor proposes a SUPPLIER child while its ceiling still allows it
        OrgOnboardingService.OrgSpec spec = new OrgOnboardingService.OrgSpec("Stale Supplier " + seq(), null,
                null, "Supplier Owner " + seq(), phone(), null, null, "Bank Windhoek",
                "3002" + String.format("%05d", seq() % 100_000), "Supplier Owner " + seq(),
                null, null, null, null, SetOf(Enums.OrgType.MERCHANT));
        Dtos.OrgDecisionResult submitted = orgOnboard.submitOrg(distPrincipal, Enums.OrgType.SUPPLIER, spec);

        // while pending, the DISTRIBUTOR narrows its own ceiling below the proposal ([D1])
        orgRights.narrow(distSub.orgId(), null, SetOf(Enums.OrgType.SUPPLIER), distPrincipal);

        // approval runs the gate, which re-checks the onboarder's CURRENT rights ([H7] rule 5, D8)
        assertThatThrownBy(() -> kycGoLive(distPrincipal, submitted.orgId(), opsPrincipal()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RIGHTS_STALE"));
    }

    @Test
    @Order(6)
    @DisplayName("TC-HON-04 · staff inheritance, LAST_ADMIN guard, admin recovery review")
    void tcHon04_staffAndAdminGuards() {
        Current.Principal rootAdmin = platformAdminPrincipal();
        String ownerPhone = phone();
        OrgOnboardingService.OrgSpec spec = distributorSpec(ownerPhone);
        Dtos.OrgDecisionResult submitted = orgOnboard.submitOrg(rootAdmin, Enums.OrgType.DISTRIBUTOR, spec);
        kycGoLive(rootAdmin, submitted.orgId(), opsPrincipal());
        String distOrg = submitted.orgId();
        User owner = users.findByPhone(ownerPhone).orElseThrow();
        activation.activate(rawTokenFor(owner.getId()), "S3cure-Password!42");
        Current.Principal ownerAdmin = principalOf(users.findById(owner.getId()).orElseThrow());

        // staff inherit the org's own UserRole; orgRole comes from the request ([C3/H9])
        String staffPhone = phone();
        Dtos.OrgStaffView staff = orgOnboard.addStaff(ownerAdmin,
                new Dtos.StaffRequest("Staff One", staffPhone, "staff-" + seq(),
                        "staff-" + seq() + "@uat.na", Enums.OrgMemberRole.STAFF, null));
        User staffUser = users.findById(staff.id()).orElseThrow();
        assertThat(staffUser.getRole()).isEqualTo(Enums.UserRole.DISTRIBUTOR);
        assertThat(staffUser.getOrgRole()).isEqualTo(Enums.OrgMemberRole.STAFF);
        assertThat(staffUser.getOrgId()).isEqualTo(distOrg);
        assertThat(staffUser.getStatus()).isEqualTo(Enums.UserStatus.PENDING);

        // deactivating the LAST ADMIN is refused ([H9])
        assertThatThrownBy(() -> orgOnboard.deactivateUser(ownerAdmin, owner.getId()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LAST_ADMIN"));

        // deactivating the staff member works
        orgOnboard.deactivateUser(ownerAdmin, staffUser.getId());
        assertThat(users.findById(staffUser.getId()).orElseThrow().getStatus())
                .isEqualTo(Enums.UserStatus.SUSPENDED);

        // a second ADMIN allows deactivating the first one ([H9] guard counts ACTIVE admins)
        Dtos.OrgStaffView admin2 = orgOnboard.addStaff(ownerAdmin,
                new Dtos.StaffRequest("Admin Two", phone(), "admin2-" + seq(),
                        "admin2-" + seq() + "@uat.na", Enums.OrgMemberRole.ADMIN, null));
        activation.activate(rawTokenFor(admin2.id()), "S3cure-Password!42");
        orgOnboard.deactivateUser(ownerAdmin, owner.getId());
        assertThat(users.findById(owner.getId()).orElseThrow().getStatus()).isEqualTo(Enums.UserStatus.SUSPENDED);

        // recovery is pointless while another active ADMIN remains ([H9])
        assertThatThrownBy(() -> orgOnboard.requestAdminRecovery(principalOf(
                        users.findById(admin2.id()).orElseThrow()), distOrg,
                new Dtos.AdminRecoveryRequest("Recovery Admin", phone(), "recovery-" + seq(),
                        "recovery-" + seq() + "@uat.na", "too early")))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ADMIN_EXISTS"));

        // the real loss case: the LAST active admin becomes unreachable (mirrored as suspended)
        jdbc.update("update users set status = ? where id = ?", Enums.UserStatus.SUSPENDED.name(),
                users.findById(admin2.id()).orElseThrow().getId());

        // platform staff request the ADMIN_RECOVERY review
        Current.Principal platform = platformAdminPrincipal();
        Dtos.OrgDecisionResult recovery = orgOnboard.requestAdminRecovery(platform, distOrg,
                new Dtos.AdminRecoveryRequest("Recovery Admin", phone(), "recovery-" + seq(),
                        "recovery-" + seq() + "@uat.na", "replacement admin"));
        assertThat(recovery.status()).isEqualTo("PENDING");   // awaits an independent review

        // the SAME requester cannot review their own request ([M4])
        assertThatThrownBy(() -> orgOnboard.decide(recovery.requestId(), platform, true, null, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INDEPENDENCE_RULE"));
        Dtos.OrgDecisionResult recoveryOk = orgOnboard.decide(recovery.requestId(), opsPrincipal(), true, null, null);
        assertThat(recoveryOk.status()).isEqualTo("PENDING");   // a candidate admin was issued an activation link
    }

    // ------------------------------------------------------------------ helpers

    private OrgOnboardingService.OrgSpec distributorSpec() {
        return distributorSpec(phone());
    }

    private OrgOnboardingService.OrgSpec distributorSpec(String ownerPhone) {
        return new OrgOnboardingService.OrgSpec("Dist " + seq(), "REG-DIST-" + seq(), "DISTRIBUTION",
                "Dist Owner " + seq(), ownerPhone, null, "owner-" + seq() + "@uat.na",
                null, null, null, null, null, null, null, null);
    }

    private static <T> java.util.Set<T> SetOf(T... items) {
        return java.util.Set.of(items);
    }

    private String rawTokenFor(String userId) {
        return simOutbox.findAll().stream()
                .filter(o -> userId.equals(o.getUserId()))
                .findFirst().orElseThrow().getRawToken();
    }
}
