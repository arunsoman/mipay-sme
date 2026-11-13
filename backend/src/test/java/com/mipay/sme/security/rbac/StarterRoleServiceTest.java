package com.mipay.sme.security.rbac;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.org.Organization;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StarterRoleServiceTest {

    private final RbacMeshClient rbac = mock(RbacMeshClient.class);
    private final StarterRoleService svc = new StarterRoleService(rbac);

    private static Organization org(String id, Enums.OrgType type) {
        Organization o = mock(Organization.class);
        when(o.getId()).thenReturn(id);
        when(o.getOrgType()).thenReturn(type);
        return o;
    }

    @Test
    void eachOrgKindMapsToItsGridRole() {
        assertThat(StarterRoleService.starterRole(Enums.OrgType.MANUFACTURER)).isEqualTo("manufacturer_role");
        assertThat(StarterRoleService.starterRole(Enums.OrgType.SUPPLIER)).isEqualTo("supplier_role");
        assertThat(StarterRoleService.starterRole(Enums.OrgType.DISTRIBUTOR)).isEqualTo("reseller_role");
        assertThat(StarterRoleService.starterRole(Enums.OrgType.MERCHANT)).isEqualTo("admin_role");
        assertThat(StarterRoleService.starterRole(Enums.OrgType.PLATFORM)).isNull();
    }

    @Test
    void manufacturerAdminGetsManufacturerRoleOnItsOwnOrg() {
        when(rbac.configured()).thenReturn(true);
        when(rbac.assignmentsFor("u1")).thenReturn(List.of());
        Organization o = org("m1", Enums.OrgType.MANUFACTURER);
        svc.grantAtGoLive(o, "u1");
        verify(rbac).assignSystem("u1", "manufacturer_role", "m1", "own", "starter role at go-live");
    }

    @Test
    void alreadyHeldIsNotGivenTwice() {
        when(rbac.configured()).thenReturn(true);
        List<Map<String, Object>> held = List.of(Map.of("role", "supplier_role", "tenant", "s1", "status", "ACTIVE"));
        when(rbac.assignmentsFor("u1")).thenReturn(held);
        Organization o = org("s1", Enums.OrgType.SUPPLIER);
        svc.grantAtGoLive(o, "u1");
        verify(rbac, never()).assignSystem(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rbacFailureNeverBlocksGoLive() {
        when(rbac.configured()).thenReturn(true);
        when(rbac.assignmentsFor("u1")).thenThrow(new IllegalStateException("rbac down"));
        Organization o = org("m1", Enums.OrgType.MANUFACTURER);
        svc.grantAtGoLive(o, "u1");   // no exception
    }
}
