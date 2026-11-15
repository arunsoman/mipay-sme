package com.mipay.sme.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.org.OrgAccessSetting;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrgAccessSettingRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.rbac.RbacMeshClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Org access by feature (docs/plans/admin-console.md).
 *
 * <p>A <b>feature</b> is one top-level menu of the org's portal (User management is the
 * "Team and access" menu). Its permissions are the permission rows of the menu's screens, with
 * every verb the code registers for them, plus the screens' menu cells ({@code nav.*}). Both come
 * from {@code rbac/feature-catalogue.json}, generated from the portals' own menu catalogue, so
 * features follow the portal automatically.
 *
 * <p>An org's switched-on features are held by its admins through ONE org-defined RBAC role,
 * {@code organization_access@<org>}, kept equal to the features (never more than the person
 * switching them holds: RBAC refuses that). RBAC already refuses roles that grant more than their
 * creator holds, so the org's admin can never build a role beyond its features. Switching a
 * feature off also strips its permissions from the org's other roles ("clamp"), so nothing the
 * org built earlier keeps it. No new enforcement is involved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgFeatureService {

    public static final String ROLE_SLUG = "organization_access";
    static final ObjectMapper JSON = new ObjectMapper();

    /** A feature that, when off, leaves the org unable to do something basic: shown as a warning. */
    static final Map<String, String> CRITICAL = new LinkedHashMap<>(Map.of(
            "team", "Can't add users",
            "onboarding", "Can't complete KYC"));

    private final OrgAccessSettingRepository settings;
    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final RbacMeshClient rbac;

    @org.springframework.beans.factory.annotation.Value("${app.rbac.mode:shadow}")
    private String rbacMode = "shadow";

    // ------------------------------------------------------------------ catalogue

    public record Screen(String id, String label, List<String> resources, String nav) { }
    public record Feature(String id, String label, List<Screen> screens) { }
    public record Preset(String code, String label, String description, List<String> features) { }

    private static final Map<String, List<Feature>> CATALOGUE = load("rbac/feature-catalogue.json",
            new TypeReference<Map<String, List<Feature>>>() { });
    private static final List<Preset> PRESETS = load("rbac/access-presets.json", new TypeReference<List<Preset>>() { });

    private static <T> T load(String path, TypeReference<T> type) {
        try (InputStream in = OrgFeatureService.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return JSON.readValue(in, type);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    /** The features of an org type's portal (empty for PLATFORM). */
    public List<Feature> features(Enums.OrgType type) {
        return CATALOGUE.getOrDefault(type.name(), List.of());
    }

    /** The presets, each resolved to the feature ids that exist in this org type's portal. */
    public List<Preset> presets(Enums.OrgType type) {
        Set<String> ids = new LinkedHashSet<>(features(type).stream().map(Feature::id).toList());
        return PRESETS.stream().map(p -> new Preset(p.code(), p.label(), p.description(),
                p.features().contains("*") ? List.copyOf(ids) : p.features().stream().filter(ids::contains).toList())).toList();
    }

    public static String roleName(String orgId) {
        return ROLE_SLUG + "@" + orgId;
    }

    // ------------------------------------------------------------------ views

    public record FeatureView(String id, String label, List<String> screens, boolean on, boolean partial) { }
    public record AccessView(String orgId, String orgName, String orgType, String preset, List<FeatureView> features,
                             List<Preset> presets, List<String> warnings, String role, Instant updatedAt,
                             Instant appliedAt, String applyError) { }

    /** The warning badges of an org (no RBAC call: used for every row of the org list). */
    public List<String> warnings(Organization org, OrgAccessSetting s) {
        if (org.getOrgType() == Enums.OrgType.PLATFORM) return List.of();
        List<String> out = new ArrayList<>();
        if (s == null) {
            out.add("Access not set");
            return out;
        }
        Set<String> on = split(s.getFeatures());
        Set<String> exists = new LinkedHashSet<>(features(org.getOrgType()).stream().map(Feature::id).toList());
        CRITICAL.forEach((id, text) -> { if (exists.contains(id) && !on.contains(id)) out.add(text); });
        if (s.getApplyError() != null) out.add("Access not applied");
        return out;
    }

    public Map<String, OrgAccessSetting> settingsOf(Collection<String> orgIds) {
        Map<String, OrgAccessSetting> m = new LinkedHashMap<>();
        for (OrgAccessSetting s : settings.findAllById(orgIds)) m.put(s.getOrgId(), s);
        return m;
    }

    @Transactional(readOnly = true)
    public AccessView view(String orgId) {
        Organization org = org(orgId);
        OrgAccessSetting s = settings.findById(orgId).orElse(null);
        Set<String> on = s == null ? Set.of() : split(s.getFeatures());
        Map<String, Set<String>> held = roleGrid(orgId);
        List<FeatureView> fs = new ArrayList<>();
        for (Feature f : features(org.getOrgType())) {
            boolean isOn = on.contains(f.id());
            // on, but the role misses some of it (whoever switched it on did not hold all of it)
            boolean partial = isOn && held != null && !covers(held, grid(List.of(f), registeredVerbs()));
            fs.add(new FeatureView(f.id(), f.label(), f.screens().stream().map(Screen::label).toList(), isOn, partial));
        }
        return new AccessView(org.getId(), org.getName(), org.getOrgType().name(), s == null ? null : s.getPresetCode(),
                fs, presets(org.getOrgType()), warnings(org, s), roleName(orgId),
                s == null ? null : s.getUpdatedAt(), s == null ? null : s.getAppliedAt(), s == null ? null : s.getApplyError());
    }

    // ------------------------------------------------------------------ writes

    /**
     * Sets an org's features, from a preset or an explicit list (explicit wins; a preset alone
     * means its features), and applies them in RBAC on behalf of {@code actor}. The choice is
     * saved even when RBAC refuses; the refusal is kept and shown as "Access not applied".
     */
    @Transactional
    public AccessView set(String orgId, String presetCode, List<String> featureIds, String actor, boolean coreAuthorized) {
        Organization org = org(orgId);
        if (org.getOrgType() == Enums.OrgType.PLATFORM) {
            throw new BadRequestException("PLATFORM_HAS_NO_FEATURES", "the platform organization's access is its root role");
        }
        Set<String> known = new LinkedHashSet<>(features(org.getOrgType()).stream().map(Feature::id).toList());
        Set<String> chosen;
        String preset = null;
        if (featureIds != null) {
            chosen = new LinkedHashSet<>(featureIds);
            List<String> unknown = chosen.stream().filter(f -> !known.contains(f)).toList();
            if (!unknown.isEmpty()) throw new BadRequestException("UNKNOWN_FEATURE", "not a menu of the " + org.getOrgType()
                    + " portal: " + unknown + "; known: " + known);
            // keep the preset label when the list still equals it
            if (presetCode != null) {
                Preset p = preset(org.getOrgType(), presetCode);
                if (Set.copyOf(p.features()).equals(chosen)) preset = p.code();
            }
        } else if (presetCode != null) {
            Preset p = preset(org.getOrgType(), presetCode);
            chosen = new LinkedHashSet<>(p.features());
            preset = p.code();
        } else {
            throw new BadRequestException("FEATURES_REQUIRED", "send a preset or a list of features");
        }

        OrgAccessSetting s = settings.findById(orgId).orElseGet(() -> {
            OrgAccessSetting n = new OrgAccessSetting();
            n.setOrgId(orgId);
            return n;
        });
        // order as the portal draws the menus
        s.setFeatures(String.join(",", known.stream().filter(chosen::contains).toList()));
        s.setPresetCode(preset);
        s.setUpdatedBy(actor);
        s.setUpdatedAt(Instant.now());
        apply(org, s, actor, coreAuthorized);
        settings.save(s);
        return view(orgId);
    }

    /**
     * Onboarding: a new org starts on a preset, saved only. RBAC learns about the org after the
     * onboarding commits (org-tree sync), so the role is built at go-live, on behalf of the person
     * who onboarded it ({@code updatedBy}). Never fails the onboarding.
     */
    @Transactional
    public void saveAtOnboarding(String orgId, Enums.OrgType type, String presetCode, String actor) {
        try {
            Preset p = preset(type, presetCode == null ? "full" : presetCode);
            OrgAccessSetting s = new OrgAccessSetting();
            s.setOrgId(orgId);
            s.setPresetCode(p.code());
            s.setFeatures(String.join(",", p.features()));
            s.setUpdatedBy(actor);
            s.setUpdatedAt(Instant.now());
            settings.save(s);
        } catch (RuntimeException e) {
            log.warn("[access] could not save the onboarding preset of {}: {}", orgId, e.getMessage());
        }
    }

    /**
     * Go-live: (re)builds the org's access role on behalf of whoever last set its features, then
     * gives it to the new admin. Orgs onboarded before features existed have no setting: nothing
     * happens, and the organizations list shows "Access not set". Never fails the go-live.
     */
    @Transactional
    public void grantAtGoLive(String orgId, String adminUid) {
        OrgAccessSetting s = settings.findById(orgId).orElse(null);
        if (s == null || !rbac.configured()) return;
        try {
            Organization org = org(orgId);
            String actor = s.getUpdatedBy() == null ? adminUid : s.getUpdatedBy();
            apply(org, s, actor, !"enforce".equalsIgnoreCase(rbacMode));
            settings.save(s);
            if (s.getApplyError() == null) {
                assignIfMissing(orgId, adminUid, actor, "kyc_go_live", "kyc go-live: the organization's features");
            }
        } catch (RuntimeException e) {
            log.error("[access] go-live grant of {} to {} FAILED: {}", roleName(orgId), adminUid, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ RBAC

    private void apply(Organization org, OrgAccessSetting s, String actor, boolean coreAuthorized) {
        if (!rbac.configured()) {
            s.setApplyError("RBAC is not configured");
            return;
        }
        String orgId = org.getId();
        try {
            Map<String, Set<String>> verbs = registeredVerbs();
            Set<String> on = split(s.getFeatures());
            Map<String, Set<String>> wanted = grid(features(org.getOrgType()).stream().filter(f -> on.contains(f.id())).toList(), verbs);
            // never more than the person switching holds (RBAC would refuse the whole role):
            // what they lack stays off and the feature shows as partial
            wanted = withinHeld(wanted, heldBy(actor, orgId));

            Map<String, Object> roles = rbac.rolesFor(orgId);
            List<Map<String, Object>> own = rows(roles.get("org_roles"));
            boolean exists = own.stream().anyMatch(r -> roleName(orgId).equals(r.get("name")));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actor", actor);
            body.put("core_authorized", coreAuthorized);
            body.put("description", "Organization access: the features this organization has been given");
            body.put("grid", toJson(wanted));
            if (exists) {
                rbac.updateRole(orgId, roleName(orgId), body);
            } else {
                body.put("name", ROLE_SLUG);
                rbac.createRole(orgId, body);
            }

            // clamp the org's other roles to what is still given
            for (Map<String, Object> r : own) {
                if (roleName(orgId).equals(r.get("name")) || Boolean.TRUE.equals(r.get("retired"))) continue;
                Map<String, Set<String>> current = gridOf(r.get("grid"));
                Map<String, Set<String>> clamped = intersect(current, wanted);
                if (!clamped.equals(current)) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("actor", actor);
                    b.put("core_authorized", coreAuthorized);
                    b.put("grid", toJson(clamped));
                    rbac.updateRole(orgId, String.valueOf(r.get("name")), b);
                    log.info("[access] {}: clamped {} to the org's features", orgId, r.get("name"));
                }
            }

            // the org's admins hold it once the org is live (before that they are KYC applicants)
            if (org.getLifecycle() == Enums.OrgStatus.ACTIVE) {
                for (var u : users.findByOrgIdAndOrgRole(orgId, Enums.OrgMemberRole.ADMIN)) {
                    if (u.getStatus() == Enums.UserStatus.ARCHIVED) continue;
                    assignIfMissing(orgId, u.getId(), actor, "org_onboarding", "the organization's features");
                }
            }
            s.setAppliedAt(Instant.now());
            s.setApplyError(null);
        } catch (RbacMeshClient.RbacMeshException e) {
            log.warn("[access] RBAC refused the features of {}: {} {}", orgId, e.code, e.getMessage());
            s.setApplyError(e.getMessage());
        }
    }

    private void assignIfMissing(String orgId, String subject, String actor, String source, String reason) {
        for (Map<String, Object> a : rbac.assignments(orgId)) {
            String st = String.valueOf(a.get("status"));
            if (subject.equals(String.valueOf(a.get("subject"))) && roleName(orgId).equals(String.valueOf(a.get("role")))
                    && ("ACTIVE".equalsIgnoreCase(st) || "PENDING".equalsIgnoreCase(st))) return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor);
        body.put("core_authorized", true);
        body.put("subject", subject);
        body.put("role", roleName(orgId));
        body.put("scope", "own");
        body.put("reason", reason);
        body.put("source", source);
        rbac.assign(orgId, body);
    }

    /** The access role's grid, or null when RBAC can't be asked. */
    private Map<String, Set<String>> roleGrid(String orgId) {
        if (!rbac.configured()) return null;
        try {
            for (Map<String, Object> r : rows(rbac.rolesFor(orgId).get("org_roles"))) {
                if (roleName(orgId).equals(r.get("name"))) return gridOf(r.get("grid"));
            }
            return Map.of();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The cells the actor holds in the org ({row → verbs}); null when RBAC can't say (no trimming). */
    private Map<String, Set<String>> heldBy(String actor, String orgId) {
        try {
            Object ops = rbac.held(actor, orgId).get("operations");
            Map<String, Set<String>> m = new TreeMap<>();
            if (ops instanceof Collection<?> c) for (Object o : c) {
                String cell = String.valueOf(o);
                int i = cell.lastIndexOf(':');
                if (i > 0) m.computeIfAbsent(cell.substring(0, i), k -> new TreeSet<>()).add(cell.substring(i + 1));
            }
            return m;
        } catch (RuntimeException e) {
            log.debug("[access] could not read what {} holds in {}: {}", actor, orgId, e.getMessage());
            return null;
        }
    }

    /** Keeps what the actor holds; menu cells ("nav.*") are exempt, as in RBAC. */
    static Map<String, Set<String>> withinHeld(Map<String, Set<String>> wanted, Map<String, Set<String>> held) {
        if (held == null) return wanted;
        Map<String, Set<String>> out = new TreeMap<>();
        for (var e : wanted.entrySet()) {
            if (e.getKey().startsWith("nav.")) { out.put(e.getKey(), e.getValue()); continue; }
            Set<String> h = held.get(e.getKey());
            if (h == null) continue;
            Set<String> v = new TreeSet<>(e.getValue());
            v.retainAll(h);
            if (!v.isEmpty()) out.put(e.getKey(), v);
        }
        return out;
    }

    /** Every permission row's registered verbs (rows the code no longer has are absent). */
    private Map<String, Set<String>> registeredVerbs() {
        Map<String, Set<String>> m = new TreeMap<>();
        try {
            for (Map<String, Object> r : rbac.platformResources()) {
                Object used = r.get("used");
                if (used instanceof Collection<?> c && !c.isEmpty()) {
                    m.put(String.valueOf(r.get("id")), new TreeSet<>(c.stream().map(String::valueOf).toList()));
                }
            }
        } catch (RuntimeException e) {
            log.debug("[access] could not read RBAC resources: {}", e.getMessage());
        }
        return m;
    }

    // ------------------------------------------------------------------ grids (pure, tested)

    /** The rows and verbs a set of features gives: screen rows with their registered verbs, plus menu cells. */
    static Map<String, Set<String>> grid(List<Feature> features, Map<String, Set<String>> registered) {
        Map<String, Set<String>> g = new TreeMap<>();
        for (Feature f : features) for (Screen s : f.screens()) {
            for (String row : s.resources()) {
                Set<String> v = registered.get(row);
                if (v != null && !v.isEmpty()) g.computeIfAbsent(row, k -> new TreeSet<>()).addAll(v);
            }
            g.computeIfAbsent(s.nav(), k -> new TreeSet<>()).add("read");
        }
        return g;
    }

    static Map<String, Set<String>> intersect(Map<String, Set<String>> a, Map<String, Set<String>> allowed) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (var e : a.entrySet()) {
            Set<String> ok = allowed.get(e.getKey());
            if (ok == null) continue;
            Set<String> v = new TreeSet<>(e.getValue());
            v.retainAll(ok);
            if (!v.isEmpty()) out.put(e.getKey(), v);
        }
        return out;
    }

    static boolean covers(Map<String, Set<String>> held, Map<String, Set<String>> wanted) {
        for (var e : wanted.entrySet()) {
            Set<String> h = held.get(e.getKey());
            if (h == null || !h.containsAll(e.getValue())) return false;
        }
        return true;
    }

    private static Map<String, List<String>> toJson(Map<String, Set<String>> g) {
        Map<String, List<String>> m = new TreeMap<>();
        g.forEach((k, v) -> m.put(k, List.copyOf(v)));
        return m;
    }

    private static Map<String, Set<String>> gridOf(Object o) {
        Map<String, Set<String>> m = new TreeMap<>();
        if (o instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                if (e.getValue() instanceof Collection<?> c) {
                    m.put(String.valueOf(e.getKey()), new TreeSet<>(c.stream().map(String::valueOf).toList()));
                }
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.stream(csv.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
    }

    private Preset preset(Enums.OrgType type, String code) {
        return presets(type).stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> new BadRequestException("UNKNOWN_PRESET", "no preset " + code));
    }

    private Organization org(String orgId) {
        return orgs.findById(orgId).orElseThrow(() -> new NotFoundException("Organization not found"));
    }
}
