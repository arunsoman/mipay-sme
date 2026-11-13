package com.mipay.sme.service;

import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.auth.UserLifecycleService;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstallerServiceTest {
    @Mock AppProperties props;
    @Mock UserRepository users;
    @Mock DeviceSessionRepository sessions;
    @Mock OrgProvisioningService orgs;
    @Mock UserLifecycleService lifecycle;
    @Mock RbacMeshClient rbac;
    @Mock ActivationService activation;
    @Mock UsernamePolicy usernamePolicy;
    @Mock PasswordEncoder encoder;
    @Mock AuditService audit;
    @InjectMocks InstallerService service;

    @Test
    void createsTemporaryAccountOnlyWithPrivateCredentialAndNoRealAdmin() {
        when(props.getIdentityMode()).thenReturn("legacy");
        when(props.getInstallerPassword()).thenReturn("a-private-credential-with-32-characters");
        when(users.findById("installer")).thenReturn(Optional.empty());
        when(users.findByRole(Enums.UserRole.MINDLABS_ADMIN)).thenReturn(List.of());
        Organization org = new Organization();
        org.setId("org_mindlabs_platform");
        when(orgs.ensurePlatformSubOrg(anyString(), anyString(), anyString())).thenReturn(org);
        when(encoder.encode(anyString())).thenReturn("{bcrypt}hash");

        service.run(null);

        var captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User installer = captor.getValue();
        assertEquals("installer", installer.getId());
        assertEquals("bootstrap:installer", installer.getCreatedBy());
        assertEquals(Enums.UserStatus.ACTIVE, installer.getStatus());
        assertEquals("{bcrypt}hash", installer.getPasswordHash());
        verify(rbac, never()).assignSystem(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void refusesWeakBootstrapCredential() {
        when(props.getIdentityMode()).thenReturn("legacy");
        when(props.getInstallerPassword()).thenReturn("short");
        assertThrows(IllegalStateException.class, () -> service.run(null));
        verifyNoInteractions(users);
    }

    @Test
    void activationRetiresInstallerAndRevokesSessions() {
        User realAdmin = new User();
        realAdmin.setId("real-admin");
        realAdmin.setCreatedBy("installer");
        realAdmin.setRole(Enums.UserRole.MINDLABS_ADMIN);
        User installer = new User();
        installer.setId("installer");
        installer.setStatus(Enums.UserStatus.ACTIVE);
        when(users.findById("installer")).thenReturn(Optional.of(installer));

        service.retireAfterActivation(realAdmin);

        assertEquals(Enums.UserStatus.ARCHIVED, installer.getStatus());
        assertNull(installer.getPasswordHash());
        verify(sessions).deleteByUserId("installer");
    }

    @Test
    void unrelatedActivationLeavesInstallerAlone() {
        User unrelated = new User();
        unrelated.setId("someone-else");
        unrelated.setCreatedBy("root-admin-bootstrap");
        unrelated.setRole(Enums.UserRole.MINDLABS_ADMIN);
        unrelated.setCreatedAt(Instant.now());
        service.retireAfterActivation(unrelated);
        verifyNoInteractions(users, sessions);
    }

    @Test
    void createsPendingAdminWithRbacRoleAndSetupLink() {
        User installer = new User();
        installer.setId("installer");
        installer.setCreatedBy("bootstrap:installer");
        installer.setCreatedAt(Instant.now());
        installer.setStatus(Enums.UserStatus.ACTIVE);
        when(users.findById("installer")).thenReturn(Optional.of(installer));
        when(users.findByRole(Enums.UserRole.MINDLABS_ADMIN)).thenReturn(List.of());
        Organization org = new Organization();
        org.setId("org_mindlabs_platform");
        when(orgs.ensurePlatformSubOrg(anyString(), anyString(), anyString())).thenReturn(org);
        Organization root = new Organization();
        root.setId("00000000-0000-0000-0000-000000000000");
        when(orgs.ensurePlatformRoot()).thenReturn(root);
        User pending = new User();
        pending.setId("new-admin");
        pending.setUsername("newadmin");
        pending.setStatus(Enums.UserStatus.PENDING);
        when(lifecycle.create(any())).thenReturn(pending);
        when(rbac.configured()).thenReturn(true);
        when(rbac.platformResources()).thenReturn(List.of(
                Map.of("id", "admin.bootstrap", "used", List.of("administer")),
                Map.of("id", "admin.payment", "used", List.of("read", "authorize", "simulate"))));
        when(rbac.platformRoles()).thenReturn(List.of());
        when(rbac.assignmentsFor("new-admin")).thenReturn(List.of());
        when(activation.issue(pending, "installer")).thenReturn("token-id");
        when(activation.linkOfToken("token-id")).thenReturn("https://example.test/activate?token=secret");

        Dtos.FirstAdminResult result = service.create(
                new Dtos.TptsBootstrapRequest("New Admin", "newadmin", "+264810000000", "new@example.test"), "installer");

        assertEquals("new-admin", result.userId());
        assertEquals("PENDING", result.status());
        assertTrue(result.setupLink().contains("/activate?token="));
        @SuppressWarnings({"unchecked", "rawtypes"}) ArgumentCaptor<Map<String, Object>> roleCaptor =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(rbac).createPlatformRole(roleCaptor.capture());
        var role = roleCaptor.getValue();
        @SuppressWarnings("unchecked") var grid = (Map<String, List<String>>) role.get("grid");
        assertEquals(List.of("administer"), grid.get("admin.bootstrap"));
        assertEquals(List.of("read"), grid.get("admin.payment"));
        verify(rbac).assignSystem("new-admin", "organization_root", root.getId(), "all", "first administrator bootstrap");
    }
}
