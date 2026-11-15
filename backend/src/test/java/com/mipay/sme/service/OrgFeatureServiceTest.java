package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.OrgAccessSetting;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrgAccessSettingRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.rbac.RbacMeshClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Org access by feature (admin-console.md): features are org-portal menus, held by the org's
 * admins through organization_access@&lt;org&gt;; switching off clamps the org's other roles.
 */
@DisplayName("Org features · menus as features, one access role, clamp on narrowing")
class OrgFeatureServiceTest {

    OrgAccessSettingRepository settings = mock(OrgAccessSettingRepository.class);
    OrganizationRepository orgs = mock(OrganizationRepository.class);
    UserRepository users = mock(UserRepository.class);
    RbacMeshClient rbac = mock(RbacMeshClient.class);
    OrgFeatureService svc = new OrgFeatureService(settings, orgs, users, rbac);

    Organization org = new Organization();
    List<Map<String, Object>> orgRoles = new ArrayList<>();
    OrgAccessSetting[] stored = new OrgAccessSetting[1];

    @BeforeEach
    void setUp() {
        org.setId("org-1");
        org.setName("Kalahari Supplies");
        org.setOrgType(Enums.OrgType.SUPPLIER);
        org.setLifecycle(Enums.OrgStatus.ACTIVE);
        when(orgs.findById("org-1")).thenReturn(Optional.of(org));
        // the repository remembers what was saved, as JPA would
        when(settings.findById("org-1")).thenAnswer(i -> Optional.ofNullable(stored[0]));
        when(settings.save(any())).thenAnswer(i -> { stored[0] = i.getArgument(0); return stored[0]; });
        when(rbac.configured()).thenReturn(true);
        when(rbac.platformResources()).thenReturn(List.of(
                Map.of("id", "org.user", "used", List.of("read", "create", "delete", "administer")),
                Map.of("id", "rbac.role", "used", List.of("read", "create")),
                Map.of("id", "rbac.assignment", "used", List.of("read", "create")),
                Map.of("id", "kyc.application", "used", List.of("read", "modify")),
                Map.of("id", "kyc.task", "used", List.of("read"))));
        when(rbac.rolesFor("org-1")).thenAnswer(i -> Map.of("org_roles", orgRoles));
        when(rbac.held(anyString(), eq("org-1"))).thenReturn(Map.of("operations", List.of(
                "org.user:read", "org.user:create", "org.user:delete", "org.user:administer",
                "rbac.role:read", "rbac.role:create", "rbac.assignment:read", "rbac.assignment:create",
                "kyc.application:read", "kyc.application:modify", "kyc.task:read")));
        when(rbac.assignments("org-1")).thenReturn(List.of());
        User admin = new User();
        admin.setId("u-admin");
        admin.setStatus(Enums.UserStatus.ACTIVE);
        when(users.findByOrgIdAndOrgRole("org-1", Enums.OrgMemberRole.ADMIN)).thenReturn(List.of(admin));
    }

    @Test
    @DisplayName("every org portal's menus are features; presets resolve to the menus that portal has")
    void catalogueAndPresets() {
        List<String> ids = svc.features(Enums.OrgType.SUPPLIER).stream().map(OrgFeatureService.Feature::id).toList();
        assertThat(ids).contains("team", "onboarding", "help");
        assertThat(svc.features(Enums.OrgType.PLATFORM)).isEmpty();
        var presets = svc.presets(Enums.OrgType.SUPPLIER);
        assertThat(presets).extracting(OrgFeatureService.Preset::code).containsExactly("full", "essentials", "kyc_only");
        assertThat(presets.get(0).features()).containsExactlyElementsOf(ids);
        // "overview" is a LENDER menu: absent from the supplier's essentials
        assertThat(presets.get(1).features()).containsExactly("home", "onboarding", "team", "help");
        assertThat(presets.get(2).features()).containsExactly("onboarding", "help");
    }

    @Test
    @DisplayName("a feature gives its screens' rows with every registered verb, plus their menu cells")
    void gridOfFeatures() {
        var team = svc.features(Enums.OrgType.SUPPLIER).stream().filter(f -> f.id().equals("team")).toList();
        var g = OrgFeatureService.grid(team, Map.of("org.user", Set.of("read", "create"), "rbac.role", Set.of("read")));
        assertThat(g).containsEntry("org.user", Set.of("create", "read"))
                .containsEntry("rbac.role", Set.of("read"))
                .containsKey("nav.supplier_team_people");
        // rbac.assignment is not registered here: absent, never invented
        assertThat(g).doesNotContainKey("rbac.assignment");
    }

    @Test
    @DisplayName("KYC only: the access role lacks org.user, so the admin can't add users (and the warning says so)")
    @SuppressWarnings("unchecked")
    void kycOnlyBlocksUserManagement() {
        var view = svc.set("org-1", "kyc_only", null, "u-root", false);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(rbac).createRole(eq("org-1"), body.capture());
        assertThat(body.getValue()).containsEntry("name", "organization_access");
        Map<String, List<String>> grid = (Map<String, List<String>>) body.getValue().get("grid");
        assertThat(grid).containsKey("kyc.application").doesNotContainKey("org.user").doesNotContainKey("rbac.role");
        // the live org's admin receives it
        verify(rbac).assign(eq("org-1"), any());
        assertThat(view.warnings()).containsExactly("Can't add users");
        assertThat(view.preset()).isEqualTo("kyc_only");
    }

    @Test
    @DisplayName("switching a feature off strips it from the org's other roles too (clamp)")
    @SuppressWarnings("unchecked")
    void clampOtherRoles() {
        orgRoles.add(Map.of("name", "organization_access@org-1", "grid", Map.of("org.user", List.of("read", "create"))));
        orgRoles.add(Map.of("name", "cashier@org-1", "grid", Map.of(
                "org.user", List.of("read"), "kyc.application", List.of("read"))));
        svc.set("org-1", null, List.of("onboarding", "help"), "u-root", false);

        verify(rbac).updateRole(eq("org-1"), eq("organization_access@org-1"), any());
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(rbac).updateRole(eq("org-1"), eq("cashier@org-1"), body.capture());
        assertThat((Map<String, List<String>>) body.getValue().get("grid"))
                .containsOnlyKeys("kyc.application");
        verify(rbac, never()).createRole(anyString(), any());
    }

    @Test
    @DisplayName("never more than the person switching holds: the rest stays off and the feature shows partial")
    void withinWhatTheActorHolds() {
        var trimmed = OrgFeatureService.withinHeld(
                Map.of("org.user", Set.of("read", "create"), "nav.supplier_team_people", Set.of("read")),
                Map.of("org.user", Set.of("read")));
        assertThat(trimmed).containsEntry("org.user", Set.of("read"))
                .containsEntry("nav.supplier_team_people", Set.of("read"));   // menu cells are exempt, as in RBAC
    }

    @Test
    @DisplayName("an RBAC refusal is kept and shown as 'Access not applied'; the choice is still saved")
    void refusalIsAWarning() {
        when(rbac.createRole(anyString(), any())).thenThrow(new RbacMeshClient.RbacMeshException(403, "insufficient_permission", "nope"));
        var view = svc.set("org-1", "full", null, "u-someone", false);
        assertThat(stored[0].getApplyError()).isEqualTo("nope");
        assertThat(stored[0].getFeatures()).contains("team");
        assertThat(view.warnings()).containsExactly("Access not applied");
    }

    @Test
    @DisplayName("a pending org's admin gets nothing until go-live; onboarding only saves the preset")
    void pendingOrgsWaitForGoLive() {
        org.setLifecycle(Enums.OrgStatus.PENDING);
        svc.set("org-1", "full", null, "u-root", false);
        verify(rbac, never()).assign(anyString(), any());

        svc.saveAtOnboarding("org-1", Enums.OrgType.SUPPLIER, null, "u-onboarder");
        ArgumentCaptor<OrgAccessSetting> s = ArgumentCaptor.forClass(OrgAccessSetting.class);
        verify(settings, atLeastOnce()).save(s.capture());
        OrgAccessSetting last = s.getValue();
        assertThat(last.getPresetCode()).isEqualTo("full");
        assertThat(last.getUpdatedBy()).isEqualTo("u-onboarder");
        assertThat(last.getAppliedAt()).isNull();
    }

    @Test
    @DisplayName("an org with no setting warns 'Access not set'; the platform never warns")
    void warnings() {
        assertThat(svc.warnings(org, null)).containsExactly("Access not set");
        Organization root = new Organization();
        root.setOrgType(Enums.OrgType.PLATFORM);
        assertThat(svc.warnings(root, null)).isEmpty();
    }
}
