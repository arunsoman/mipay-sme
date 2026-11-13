package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.auth.UserLifecycleService;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One temporary portal account creates the first real administrator, then is archived. */
@Service
@RequiredArgsConstructor
public class InstallerService implements ApplicationRunner {
    private static final String INSTALLER = "installer";
    private static final String MARKER = "bootstrap:installer";
    private static final String ROOT_ROLE = "organization_root";

    private final AppProperties props;
    private final UserRepository users;
    private final DeviceSessionRepository sessions;
    private final OrgProvisioningService orgs;
    private final UserLifecycleService lifecycle;
    private final RbacMeshClient rbac;
    private final ActivationService activation;
    private final UsernamePolicy usernamePolicy;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = props.getInstallerPassword();
        if (!"legacy".equals(props.getIdentityMode()) || password == null || password.isBlank()) return;
        if (password.length() < 24) throw new IllegalStateException("APP_INSTALLER_PASSWORD needs at least 24 characters");
        if (users.findById(INSTALLER).isPresent()) return; // never recreate a retired or expired installer
        if (users.findByRole(Enums.UserRole.MINDLABS_ADMIN).stream()
                .anyMatch(u -> u.getStatus() == Enums.UserStatus.ACTIVE)) return;
        var root = orgs.ensurePlatformSubOrg("mindlabs", "MiNdLABS (operator)", "org_mindlabs_platform");
        User user = new User();
        user.setId(INSTALLER);
        user.setUsername(INSTALLER);
        user.setName("Temporary installer");
        user.setPhone("setup-installer");
        user.setPasswordHash(encoder.encode(password));
        user.setRole(Enums.UserRole.MINDLABS_ADMIN);
        user.setOrgRole(Enums.OrgMemberRole.ADMIN);
        user.setOrgId(root.getId());
        user.setStatus(Enums.UserStatus.ACTIVE);
        user.setCreatedBy(MARKER);
        users.save(user);
        audit.record(INSTALLER, "SYSTEM", "installer_created", "user", INSTALLER,
                null, "ACTIVE", "expires in 72h", false, null);
    }

    /** Idempotent retry after an RBAC/network failure; never accepts a second first admin. */
    @Transactional
    public Dtos.FirstAdminResult create(Dtos.TptsBootstrapRequest request, String actor) {
        if (!INSTALLER.equals(actor)) throw new ForbiddenException("INSTALLER_ONLY", "Temporary installer only");
        User installer = users.findById(INSTALLER).orElseThrow(() -> new ForbiddenException("INSTALLER_MISSING", "Installer missing"));
        if (!MARKER.equals(installer.getCreatedBy()) || installer.getStatus() != Enums.UserStatus.ACTIVE
                || installer.getCreatedAt() == null || !installer.getCreatedAt().plusSeconds(72 * 3600).isAfter(Instant.now())) {
            throw new ForbiddenException("INSTALLER_EXPIRED", "Installer account expired");
        }
        if (request == null || blank(request.name()) || blank(request.username()) || blank(request.phone())) {
            throw new com.mipay.sme.common.SimException("VALIDATION", "name, username and phone are required");
        }
        User first = users.findByRole(Enums.UserRole.MINDLABS_ADMIN).stream()
                .filter(u -> !INSTALLER.equals(u.getId()) && u.getStatus() == Enums.UserStatus.ACTIVE)
                .findFirst().orElse(null);
        if (first != null) throw new ConflictException("ALREADY_BOOTSTRAPPED", "An active administrator already exists");

        User pending = users.findByRole(Enums.UserRole.MINDLABS_ADMIN).stream()
                .filter(u -> INSTALLER.equals(u.getCreatedBy()) && u.getStatus() == Enums.UserStatus.PENDING)
                .findFirst().orElse(null);
        if (pending != null && (!request.username().equals(pending.getUsername())
                || !request.phone().equals(pending.getPhone()))) {
            throw new ConflictException("ADMIN_PENDING", "Finish setting up the pending first administrator");
        }
        if (pending == null) usernamePolicy.validate(request.username());
        var root = orgs.ensurePlatformSubOrg("mindlabs", "MiNdLABS (operator)", "org_mindlabs_platform");
        if (pending == null) {
            pending = lifecycle.create(new UserLifecycleService.Registration(root.getId(), request.name(),
                    request.username(), request.phone(), request.email(), Enums.UserRole.MINDLABS_ADMIN,
                    Enums.OrgMemberRole.ADMIN, INSTALLER));
        }
        ensureRbac(pending.getId());
        String tokenId = activation.issue(pending, INSTALLER);
        audit.record(INSTALLER, "MINDLABS_ADMIN", "first_admin_enrolled", "user", pending.getId(),
                null, "PENDING", "role=" + ROOT_ROLE, false, null);
        return new Dtos.FirstAdminResult(pending.getId(), pending.getUsername(),
                pending.getStatus().name(), activation.linkOfToken(tokenId));
    }

    private void ensureRbac(String subject) {
        if (!rbac.configured()) throw new IllegalStateException("RBAC is required for first admin bootstrap");
        List<Map<String, Object>> resources = rbac.platformResources();
        if (resources.stream().noneMatch(r -> "admin.bootstrap".equals(r.get("id"))
                && ((List<?>) r.get("used")).contains("administer"))) {
            throw new IllegalStateException("RBAC has not registered the first-admin endpoint yet; retry shortly");
        }
        if (rbac.platformRoles().stream().noneMatch(r -> ROOT_ROLE.equals(r.get("name")))) {
            Map<String, List<String>> grid = new LinkedHashMap<>();
            for (Map<String, Object> row : resources) {
                String id = String.valueOf(row.get("id"));
                @SuppressWarnings("unchecked") List<String> used = (List<String>) row.get("used");
                List<String> allowed = used.stream().filter(v -> !v.equals("authorize") && !v.equals("simulate")).toList();
                if (!allowed.isEmpty()) grid.put(id, allowed);
            }
            rbac.createPlatformRole(Map.of("actor", INSTALLER, "core_authorized", false,
                    "name", ROOT_ROLE, "description", "First platform administrator (no approval authority)",
                    "kind", "PLATFORM", "grid", grid));
        }
        String tenant = orgs.ensurePlatformRoot().getId();
        if (rbac.assignmentsFor(subject).stream().noneMatch(a -> ROOT_ROLE.equals(a.get("role"))
                && "ACTIVE".equals(a.get("status")))) {
            rbac.assignSystem(subject, ROOT_ROLE, tenant, "all", "first administrator bootstrap");
        }
    }

    @Transactional
    public void retireAfterActivation(User activated) {
        if (!INSTALLER.equals(activated.getCreatedBy()) || activated.getRole() != Enums.UserRole.MINDLABS_ADMIN) return;
        users.findById(INSTALLER).ifPresent(installer -> {
            if (installer.getStatus() != Enums.UserStatus.ACTIVE) return;
            installer.setStatus(Enums.UserStatus.ARCHIVED);
            installer.setPasswordHash(null);
            users.save(installer);
            sessions.deleteByUserId(INSTALLER);
            audit.record(activated.getId(), activated.getRole().name(), "installer_retired", "user", INSTALLER,
                    "ACTIVE", "ARCHIVED", "first admin activated", false, null);
        });
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
