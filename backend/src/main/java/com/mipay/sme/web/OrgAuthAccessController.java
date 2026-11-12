package com.mipay.sme.web;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.auth.AuthProviderRegistry;
import com.mipay.sme.security.auth.AuthOutboxService;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.OrgTreeService;
import com.mipay.sme.domain.org.Organization;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Org-scoped lifecycle actions over its own users (IdP/RBAC cutover plan §2: "org admins
 * manage credentials for their org's users"). Every mutation only enqueues an outbox command —
 * the provider is touched by the delivery loop, so a half-applied action can never exist.
 */
@RestController
@RequestMapping("/api/orgs/{id}/auth")
@RequiredArgsConstructor
public class OrgAuthAccessController {
    private final UserRepository users;
    private final IdentityLinkRepository links;
    private final AuthOutboxService outbox;
    private final AuthProviderRegistry registry;
    private final OrgTreeService tree;
    private final OrgClosureRepository closureRepo;

    @Perm(resource = "org.user", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "View an org's users and their IdP credential state (PATH tenant)")
    @GetMapping("/users")
    public List<Map<String, Object>> users(@PathVariable String id) {
        requireManager(id);
        return users.findByOrgId(id).stream()
                .sorted(java.util.Comparator.comparing(User::getName,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .map(u -> view(u, firstLink(u.getId()))).toList();
    }

    @Perm(resource = "org.user", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Re-send a user's one-time set-up link (PATH tenant)")
    @PostMapping("/users/{uid}/resend-link")
    public Map<String, Object> resendLink(@PathVariable String id, @PathVariable String uid) {
        requireManager(id);
        User user = userIn(id, uid);
        IdentityLink link = credentialOf(user);
        outbox.enqueue(uid, key(), "RESET_LINK",
                Map.of("user_id", uid, "subject", link.getSubject(), "scope", "LINK", "requested_by", Current.get().userId()),
                "reset:" + uid + ":" + link.getId());
        return Map.of("enqueued", true, "user_id", uid, "scope", "LINK");
    }

    @Perm(resource = "org.user", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Force a full credential reset for a user (invalidates current credentials) (PATH tenant)")
    @PostMapping("/users/{uid}/reset-credential")
    public Map<String, Object> resetCredential(@PathVariable String id, @PathVariable String uid) {
        requireManager(id);
        User user = userIn(id, uid);
        IdentityLink link = credentialOf(user);
        outbox.enqueue(uid, key(), "RESET_LINK",
                Map.of("user_id", uid, "subject", link.getSubject(), "scope", "FULL", "requested_by", Current.get().userId()),
                "reset:" + uid + ":" + link.getId());
        return Map.of("enqueued", true, "user_id", uid, "scope", "FULL");
    }

    @Perm(resource = "org.user", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Revoke all IdP sessions for a user (PATH tenant)")
    @PostMapping("/users/{uid}/revoke-sessions")
    public Map<String, Object> revokeSessions(@PathVariable String id, @PathVariable String uid) {
        requireManager(id);
        User user = userIn(id, uid);
        IdentityLink link = credentialOf(user);
        outbox.enqueue(uid, key(), "REVOKE_SESSIONS",
                Map.of("user_id", uid, "subject", link.getSubject(), "requested_by", Current.get().userId()),
                "revoke:" + uid + ":" + Current.get().userId());
        return Map.of("enqueued", true, "user_id", uid);
    }

    private String key() {
        return registry.enabled() ? registry.selected().key() : "trustplane";
    }

    private User userIn(String id, String uid) {
        User user = users.findById(uid).orElseThrow(() -> new ForbiddenException("USER_NOT_FOUND", "User not found"));
        if (!id.equals(user.getOrgId())) throw new ForbiddenException("USER_NOT_IN_ORG", "User does not belong to this org");
        return user;
    }

    private IdentityLink firstLink(String uid) {
        return links.findByUserId(uid).stream().findFirst().orElse(null);
    }

    /** A reset command is meaningless without a delivered credential (link with a subject). */
    private IdentityLink credentialOf(User user) {
        IdentityLink link = firstLink(user.getId());
        if (link == null || link.getSubject() == null || link.getSubject().isBlank())
            throw new ConflictException("NO_CREDENTIAL", "User has no delivered IdP credential to act on");
        return link;
    }

    private Map<String, Object> view(User u, IdentityLink link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("username", u.getUsername());
        m.put("phone", u.getPhone());
        m.put("status", u.getStatus() == null ? null : u.getStatus().name());
        m.put("org_role", u.getOrgRole() == null ? null : u.getOrgRole().name());
        m.put("subject", link == null ? null : link.getSubject());
        m.put("link_status", link == null ? null : link.getStatus() == null ? null : link.getStatus().name());
        m.put("idp_enabled", link != null && link.isIdpEnabled());
        return m;
    }

    /** Platform staff, or an ADMIN of this org or of an org above it (same rule as role management). */
    private Current.Principal requireManager(String orgId) {
        Current.Principal caller = Current.get();
        tree.visible(caller, orgId);
        if (tree.platformStaff(caller)) return caller;
        Organization own = tree.orgOf(caller);
        boolean ok = caller.isOrgAdmin() && own != null
                && (own.getId().equals(orgId) || closureRepo.existsByAncestorIdAndDescendantId(own.getId(), orgId));
        if (!ok) throw new ForbiddenException("ORG_ADMIN_REQUIRED",
                "Only an admin of this org or an org above it manages its users' credentials");
        return caller;
    }
}
