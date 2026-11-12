package com.mipay.sme.uat;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.service.AdminService;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OA1 (org-access-model D-1/D-3/D-9): the shared platform root splits into the MiNdLABS
 * operator org and the TPTS operating root. The seeder parks platform staff in MiNdLABS
 * (this environment has no users when V18 runs, so the bootstrap — not the migration —
 * creates TPTS, exactly like a fresh production deployment). TC-OA1.
 */
@DisplayName("OA1 · TPTS operating-root bootstrap — TC-OA1")
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TUat21TptsRootBootstrapTests extends UatSupport {

    @Autowired protected AdminService adminService;
    @Autowired protected com.mipay.sme.repository.OrgClosureRepository closures;

    private String mindlabsId;
    private String tptsId;

    @Test
    @Order(1)
    @DisplayName("TC-OA1-01 · Seeder parks platform staff in the MiNdLABS operator org")
    void tcOa101_staffParkedInMindlabs() {
        Organization mindlabs = orgs.findByOrgTypeAndEntityId(Enums.OrgType.PLATFORM, "mindlabs").orElseThrow();
        mindlabsId = mindlabs.getId();
        assertThat(mindlabs.getLifecycle()).isEqualTo(Enums.OrgStatus.ACTIVE);
        // the seeder's staff sits in MiNdLABS, not on the shared root shell
        assertThat(users.findAll().stream()
                .filter(u -> u.getRole() == Enums.UserRole.TPTS_OPS || u.getRole() == Enums.UserRole.MINDLABS_ADMIN))
                .allSatisfy(u -> assertThat(u.getOrgId()).isEqualTo(mindlabsId));
        // and no TPTS org exists yet — the migration leaves it to the bootstrap on clean envs
        assertThat(orgs.findByOrgTypeAndEntityId(Enums.OrgType.PLATFORM, "tpts")).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("TC-OA1-02 · The one-time bootstrap creates TPTS, moves TPTS staff, enrols the first admin")
    void tcOa102_bootstrapCreatesTpts() {
        var actor = platformAdminPrincipal();
        Dtos.TptsBootstrapResult result = adminService.bootstrapTpts(new Dtos.TptsBootstrapRequest(
                "TPTS Root Admin", "tpts-root-admin", "0837777777", "tpts-admin-" + seq() + "@uat.na"),
                actor.userId(), "MINDLABS_ADMIN");
        tptsId = result.orgId();

        Organization tpts = orgs.findById(tptsId).orElseThrow();
        assertThat(tpts.getOrgType()).isEqualTo(Enums.OrgType.PLATFORM);
        assertThat(tpts.getEntityId()).isEqualTo("tpts");
        assertThat(tpts.getLifecycle()).isEqualTo(Enums.OrgStatus.ACTIVE);
        assertThat(orgs.findById(tpts.getId()).orElseThrow().getParentId())
                .isEqualTo(((Organization) orgs.findAll().stream()
                        .filter(o -> o.getOrgType() == Enums.OrgType.PLATFORM
                                && o.getEntityId() == null).findFirst().orElseThrow()).getId());
        assertThat(closures.existsByAncestorIdAndDescendantId(tpts.getId(), tpts.getId())).isTrue();
        assertThat(closures.findDirectParent(tpts.getId())).isNotNull();

        // every TPTS_OPS staff member moved out of MiNdLABS; the MiNdLABS admin stayed
        assertThat(users.findAll().stream()
                .filter(u -> u.getRole() == Enums.UserRole.TPTS_OPS))
                .allSatisfy(u -> assertThat(u.getOrgId()).isEqualTo(tptsId));
        assertThat(users.findAll().stream()
                .filter(u -> u.getRole() == Enums.UserRole.MINDLABS_ADMIN))
                .allSatisfy(u -> assertThat(u.getOrgId()).isEqualTo(mindlabsId));

        // the first TPTS admin is enrolled (PENDING) — credentials come only through the set-up link
        var first = users.findById(result.firstAdminId()).orElseThrow();
        assertThat(first.getOrgId()).isEqualTo(tptsId);
        assertThat(first.getStatus()).isEqualTo(Enums.UserStatus.PENDING);
        assertThat(first.getOrgRole()).isEqualTo(Enums.OrgMemberRole.ADMIN);
        assertThat(result.staffMoved()).isEqualTo((int) users.findAll().stream()
                .filter(u -> u.getRole() == Enums.UserRole.TPTS_OPS && !u.getId().equals(first.getId())).count());
        assertThat(auditRepo.findAll().stream()
                .anyMatch(a -> "tpts_root_bootstrapped".equals(a.getAction()))).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("TC-OA1-03 · The bootstrap is one-time: a second call refuses, nothing changes")
    void tcOa103_bootstrapRefusesWhenTptsExists() {
        long orgCount = orgs.count();
        var actor = platformAdminPrincipal();
        assertThatThrownBy(() -> adminService.bootstrapTpts(new Dtos.TptsBootstrapRequest(
                "Second Admin", "second-admin", "0838888888", "second-admin-" + seq() + "@uat.na"),
                actor.userId(), "MINDLABS_ADMIN"))
                .isInstanceOf(SimException.class)
                .hasMessageContaining("one-time");
        assertThat(orgs.count()).isEqualTo(orgCount);
        assertThat(orgs.findByOrgTypeAndEntityId(Enums.OrgType.PLATFORM, "tpts").orElseThrow().getId())
                .isEqualTo(tptsId);
    }
}
