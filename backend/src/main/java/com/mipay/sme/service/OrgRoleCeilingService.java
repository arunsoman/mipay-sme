package com.mipay.sme.service;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.org.OrgRoleCeiling;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrgRoleCeilingRepository;
import com.mipay.sme.repository.OrgSubtreeRow;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.OrgTreeSyncService;
import com.mipay.sme.security.rbac.RbacMeshClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Role ceilings per org (docs/plans/extended-rbac.md X1-X6). The core is the authority; the
 * org-tree sync carries every explicit ceiling to RBAC, which enforces it (X7) and re-applies
 * "child within parent" on arrival. The rules here mirror RBAC's {@code Ceilings}:
 * <ul>
 *   <li>explicit ceiling: {@code roles} (+ baseline) and {@code delegable} (⊆ roles);</li>
 *   <li>none: the nearest ancestor's delegable set, as both; none above: unrestricted;</li>
 *   <li>a child never exceeds its parent's delegable set (+ baseline); narrowing cascades.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgRoleCeilingService {

    /** One role of the catalogue the ceilings choose from. */
    public record CatalogueRole(String name, String description) { }

    /** roles / delegable null = unrestricted; from = the org whose explicit ceiling applies. */
    public record Effective(String orgId, boolean explicit, String from, Set<String> roles, Set<String> delegable) {
        public boolean unrestricted() {
            return roles == null;
        }
    }

    public record Normalized(Set<String> roles, Set<String> delegable) { }

    private final OrgRoleCeilingRepository repo;
    private final OrgClosureRepository closureRepo;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final RbacMeshClient rbac;

    /** Inside every ceiling (grid seed v1 ceiling_baseline, now database-owned); RBAC enforces its own copy. */
    @Value("${app.rbac.ceiling-baseline:kyc_applicant,org_admin}")
    private List<String> baseline;

    /** Used when RBAC can't be asked for its catalogue (no mesh in tests and the H2 demo). */
    @Value("${app.rbac.org-roles:kyc_applicant,org_viewer,org_staff,org_admin}")
    private List<String> fallbackCatalogue;

    private volatile List<CatalogueRole> cached;
    private volatile long cachedAt;

    public Set<String> baseline() {
        return new LinkedHashSet<>(baseline);
    }

    // ------------------------------------------------------------------
    // Catalogue: the global ORG roles RBAC knows (descriptions for the UI)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public List<CatalogueRole> catalogue() {
        List<CatalogueRole> c = cached;
        if (c != null && System.currentTimeMillis() - cachedAt < 60_000) return c;
        List<CatalogueRole> out = new ArrayList<>();
        if (rbac.configured()) {
            try {
                Map<String, Object> view = rbac.rolesFor(OrgProvisioningService.ORG_ROOT);
                for (Object o : (List<Object>) view.getOrDefault("roles", List.of())) {
                    Map<String, Object> r = (Map<String, Object>) o;
                    out.add(new CatalogueRole(String.valueOf(r.get("name")), String.valueOf(r.getOrDefault("description", ""))));
                }
            } catch (RuntimeException e) {
                log.warn("[ceilings] RBAC catalogue unavailable, using app.rbac.org-roles: {}", e.getMessage());
            }
        }
        if (out.isEmpty()) {
            for (String n : fallbackCatalogue) out.add(new CatalogueRole(n, ""));
        } else {
            cached = out;
            cachedAt = System.currentTimeMillis();
        }
        return out;
    }

    Set<String> catalogueNames() {
        Set<String> out = new LinkedHashSet<>();
        for (CatalogueRole r : catalogue()) out.add(r.name());
        return out;
    }

    // ------------------------------------------------------------------
    // Effective ceiling
    // ------------------------------------------------------------------

    public Effective effective(String orgId) {
        List<OrgSubtreeRow> chain = new ArrayList<>(closureRepo.findAncestors(orgId)); // root first
        java.util.Collections.reverse(chain);                                          // self first
        List<String> ids = new ArrayList<>();
        for (OrgSubtreeRow r : chain) ids.add(r.getOrg().getId());
        if (!ids.contains(orgId)) ids.add(0, orgId);
        for (String id : ids) {
            Optional<OrgRoleCeiling> row = repo.findById(id);
            if (row.isEmpty()) continue;
            boolean self = id.equals(orgId);
            Set<String> roles = new LinkedHashSet<>(self ? row.get().roleSet() : row.get().delegableSet());
            Set<String> deleg = new LinkedHashSet<>(row.get().delegableSet());
            if (self) deleg.retainAll(row.get().roleSet());
            roles.addAll(baseline);
            return new Effective(orgId, self, id, roles, deleg);
        }
        return new Effective(orgId, false, null, null, null);
    }

    /** What an org may hand down to a child it onboards (the catalogue when unrestricted). */
    public Set<String> handDown(String orgId) {
        Effective e = effective(orgId);
        return e.delegable() == null ? catalogueNames() : new LinkedHashSet<>(e.delegable());
    }

    /**
     * Validates a proposed ceiling for a child of {@code parentOrgId} (X4). Returns it with
     * the baseline added; throws 422 ROLES_EXCEED_CEILING / ROLES_INVALID.
     */
    public Normalized validate(String parentOrgId, Collection<String> roles, Collection<String> delegable) {
        Set<String> r = clean(roles), d = clean(delegable);
        Set<String> known = catalogueNames();
        known.addAll(baseline);
        Set<String> unknown = new TreeSet<>(r);
        unknown.addAll(d);
        unknown.removeAll(known);
        if (!unknown.isEmpty()) throw new SimException("ROLES_INVALID", "Unknown or non-org roles: " + unknown);
        Set<String> allowed = parentOrgId == null ? known : handDown(parentOrgId);
        allowed.addAll(baseline);
        Set<String> beyond = new TreeSet<>(r);
        beyond.removeAll(allowed);
        if (!beyond.isEmpty()) {
            throw new SimException("ROLES_EXCEED_CEILING", "The onboarding org may not hand down: " + beyond);
        }
        r.addAll(baseline);
        Set<String> notHeld = new TreeSet<>(d);
        notHeld.removeAll(r);
        if (!notHeld.isEmpty()) throw new SimException("ROLES_INVALID", "May only hand down roles it may use: " + notHeld);
        return new Normalized(r, d);
    }

    private static Set<String> clean(Collection<String> c) {
        Set<String> out = new LinkedHashSet<>();
        if (c != null) for (String s : c) if (s != null && !s.isBlank()) out.add(s.trim());
        return out;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Sets an explicit ceiling (validated against the parent), then clamps the subtree. */
    @Transactional
    public Map<String, Object> set(String orgId, Collection<String> roles, Collection<String> delegable, String actorId, String actorRole) {
        Normalized n = validate(closureRepo.findDirectParent(orgId), roles, delegable);
        OrgRoleCeiling row = repo.findById(orgId).orElseGet(() -> {
            OrgRoleCeiling c = new OrgRoleCeiling();
            c.setOrgId(orgId);
            return c;
        });
        row.setRoleSet(n.roles());
        row.setDelegableSet(n.delegable());
        row.setSetBy(actorId);
        repo.save(row);
        List<String> clamped = clampBelow(orgId);
        auditService.record(actorId, actorRole, "org_role_ceiling_set", "organization", orgId, null, null,
                "roles=" + new TreeSet<>(n.roles()) + ";delegable=" + new TreeSet<>(n.delegable()) + ";clamped=" + clamped, false, null);
        events.publishEvent(new OrgTreeSyncService.OrgTreeChanged(orgId));
        return view(orgId);
    }

    /** Removes the explicit ceiling: the org inherits again (X2). */
    @Transactional
    public Map<String, Object> clear(String orgId, String actorId, String actorRole) {
        repo.findById(orgId).ifPresent(repo::delete);
        repo.flush();
        List<String> clamped = clampBelow(orgId);
        auditService.record(actorId, actorRole, "org_role_ceiling_cleared", "organization", orgId, null, null,
                "clamped=" + clamped, false, null);
        events.publishEvent(new OrgTreeSyncService.OrgTreeChanged(orgId));
        return view(orgId);
    }

    /** Child within parent, top-down over the subtree (X4). Returns the orgs that narrowed. */
    List<String> clampBelow(String orgId) {
        Set<String> base = baseline();
        List<String> changed = new ArrayList<>();
        for (OrgSubtreeRow r : closureRepo.findSubtree(orgId)) {   // depth ascending
            String id = r.getOrg().getId();
            if (id.equals(orgId)) continue;
            Optional<OrgRoleCeiling> row = repo.findById(id);
            if (row.isEmpty()) continue;
            String parent = closureRepo.findDirectParent(id);
            Effective p = parent == null ? null : effective(parent);
            Set<String> roles = row.get().roleSet(), deleg = row.get().delegableSet();
            Set<String> newRoles = new LinkedHashSet<>(roles);
            if (p != null && p.delegable() != null) newRoles.removeIf(x -> !p.delegable().contains(x) && !base.contains(x));
            Set<String> newDeleg = new LinkedHashSet<>(deleg);
            newDeleg.retainAll(newRoles);
            if (!newRoles.equals(roles) || !newDeleg.equals(deleg)) {
                row.get().setRoleSet(newRoles);
                row.get().setDelegableSet(newDeleg);
                repo.save(row.get());
                changed.add(id);
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    public Map<String, Object> view(String orgId) {
        Effective e = effective(orgId);
        Set<String> all = catalogueNames();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgId", orgId);
        m.put("explicit", e.explicit());
        m.put("inheritedFrom", e.explicit() ? null : e.from());
        m.put("unrestricted", e.unrestricted());
        m.put("roles", new ArrayList<>(new TreeSet<>(e.roles() == null ? all : e.roles())));
        m.put("delegableRoles", new ArrayList<>(new TreeSet<>(e.delegable() == null ? all : e.delegable())));
        m.put("baselineRoles", new ArrayList<>(baseline));
        String parent = closureRepo.findDirectParent(orgId);
        m.put("parentHandDown", parent == null ? new ArrayList<>(new TreeSet<>(all)) : new ArrayList<>(new TreeSet<>(handDown(parent))));
        m.put("catalogue", catalogue());
        return m;
    }

    /** For the org-tree sync: every explicit ceiling (org id → [roles, delegable]). */
    public Map<String, List<List<String>>> explicitCeilings() {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        for (OrgRoleCeiling c : repo.findAll()) {
            out.put(c.getOrgId(), List.of(new ArrayList<>(c.roleSet()), new ArrayList<>(c.delegableSet())));
        }
        return out;
    }

    /** The authority check used by the controller: who may set a child's ceiling (X5). */
    public static boolean isStrictAncestorAdmin(Current.Principal caller, String callerOrgId, String orgId,
                                                OrgClosureRepository closureRepo) {
        return caller.isOrgAdmin() && callerOrgId != null && !callerOrgId.equals(orgId)
                && closureRepo.existsByAncestorIdAndDescendantId(callerOrgId, orgId);
    }
}
