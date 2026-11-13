package com.mipay.sme.service.migration;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrgRightRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserPlatformFunctionRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.service.OrgProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** organization_root goes to the root org's ADMINs only, never by legacy role. */
class RbacBackfillTest {

    private static final String ROOT = "00000000-0000-0000-0000-000000000000";

    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final OrgRightRepository rights = mock(OrgRightRepository.class);
    private final UserPlatformFunctionRepository functions = mock(UserPlatformFunctionRepository.class);
    private final RbacMeshClient rbac = mock(RbacMeshClient.class);
    private final OrgProvisioningService provisioning = mock(OrgProvisioningService.class);
    private final RbacBackfill backfill = new RbacBackfill(users, orgs, rights, functions, rbac, provisioning);

    @BeforeEach
    void setUp() {
        Organization root = mock(Organization.class);
        when(root.getId()).thenReturn(ROOT);
        Organization platform = mock(Organization.class);
        when(platform.getId()).thenReturn("org_tpts_platform");
        when(platform.getOrgType()).thenReturn(Enums.OrgType.PLATFORM);
        when(provisioning.ensurePlatformRoot()).thenReturn(root);
        when(orgs.findById("org_tpts_platform")).thenReturn(Optional.of(platform));
        when(rbac.configured()).thenReturn(true);
        when(rbac.assignmentsFor(anyString())).thenReturn(List.of());
        when(rights.findAll()).thenReturn(List.of());
    }

    private static User user(String id, Enums.UserRole role, Enums.OrgMemberRole orgRole) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getOrgId()).thenReturn("org_tpts_platform");
        when(u.getStatus()).thenReturn(Enums.UserStatus.ACTIVE);
        when(u.getRole()).thenReturn(role);
        when(u.getOrgRole()).thenReturn(orgRole);
        return u;
    }

    @Test
    void platformAdminGetsOrganizationRoot() {
        List<User> all = List.of(user("admin", Enums.UserRole.MINDLABS_ADMIN, Enums.OrgMemberRole.ADMIN));
        when(users.findAll()).thenReturn(all);
        backfill.run();
        verify(rbac).assignSystem(eq("admin"), eq("organization_root"), eq(ROOT), eq("all"), anyString());
    }

    @Test
    void platformStaffWithAnAdminLegacyRoleDoesNotGetOrganizationRoot() {
        // staff added through User management inherit their creator's MINDLABS_ADMIN legacy role
        List<User> all = List.of(
                user("staff", Enums.UserRole.MINDLABS_ADMIN, Enums.OrgMemberRole.STAFF),
                user("ops", Enums.UserRole.TPTS_OPS, Enums.OrgMemberRole.STAFF));
        when(users.findAll()).thenReturn(all);
        backfill.run();
        verify(rbac, never()).assignSystem(anyString(), eq("organization_root"), anyString(), anyString(), anyString());
    }
}
