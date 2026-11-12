package com.mipay.sme.config;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.auth.UserLifecycleService;
import com.mipay.sme.service.OrgProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Root administrator recovery: when the root organization (TPTS, the operating root) has no
 * administrator — a fresh install, or after every user was deleted — one is created from
 * APP_ROOT_ADMIN_NAME / _PHONE / _EMAIL (/ _USERNAME). It starts PENDING and gets its
 * credentials through the normal one-time set-up link; RBAC gives it organization_root
 * (every permission) through the boot backfill. Nothing happens while an administrator exists.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RootAdminBootstrap {

    private final UserRepository users;
    private final OrgProvisioningService provisioning;
    private final UserLifecycleService lifecycle;

    @Value("${app.root-admin.name:}") private String name;
    @Value("${app.root-admin.phone:}") private String phone;
    @Value("${app.root-admin.email:}") private String email;
    @Value("${app.root-admin.username:}") private String username;

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional
    public void ensureRootAdmin() {
        if (phone == null || phone.isBlank()) return;
        var root = provisioning.ensurePlatformSubOrg("tpts", "TPTS (operating root)", "org_tpts_platform");
        boolean hasAdmin = users.findByOrgId(root.getId()).stream()
                .anyMatch(u -> u.getOrgRole() == Enums.OrgMemberRole.ADMIN && u.getStatus() != Enums.UserStatus.ARCHIVED);
        if (hasAdmin) return;
        User admin = lifecycle.create(new UserLifecycleService.Registration(root.getId(),
                name == null || name.isBlank() ? "Root administrator" : name,
                username == null || username.isBlank() ? null : username, phone,
                email == null || email.isBlank() ? null : email,
                Enums.UserRole.MINDLABS_ADMIN, Enums.OrgMemberRole.ADMIN, "root-admin-bootstrap"));
        log.warn("[root-admin] the root organization had no administrator: created {} ({}) PENDING; "
                + "the set-up link is in the activation outbox", admin.getId(), admin.getUsername());
    }
}
