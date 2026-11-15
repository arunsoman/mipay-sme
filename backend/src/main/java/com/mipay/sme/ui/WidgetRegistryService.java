package com.mipay.sme.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.domain.ui.UiWidgetRegistration;
import com.mipay.sme.repository.UiWidgetRegistrationRepository;
import com.mipay.sme.security.rbac.EndpointManifestPublisher;
import com.mipay.sme.security.rbac.EndpointManifestPublisher.Endpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The UI widget registry (docs/plans/portal-builder.md §7, decision 6/7).
 *
 * A portal build registers the manifests of the widgets it carries (never code). Before anything
 * is stored, every widget is checked against THIS backend's @Perm endpoint manifest:
 * <ul>
 *   <li>each route a widget can call must be served here, for that method and path;</li>
 *   <li>each such endpoint's permission cell must be one the widget declares (requires or an
 *       action) - otherwise the builder and the RBAC screen would promise a widget that the
 *       server then refuses (and auto-hide would mask the bug);</li>
 *   <li>every declared cell must exist in the permission catalogue.</li>
 * </ul>
 * A registration is all or nothing. On success, the portal's ACTIVE set becomes exactly the
 * build's widgets: new or changed ones are added, ones the build no longer carries are retired.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WidgetRegistryService {

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Pattern PORTAL = Pattern.compile("^(PLATFORM|LENDER|MERCHANT|MANUFACTURER|DISTRIBUTOR|SUPPLIER|CUSTOMER)$");
    private static final Pattern BUNDLE = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,80}@[A-Za-z0-9._-]{1,100}$");
    private static final Pattern PATH_VAR = Pattern.compile("\\{[^}]+}");

    private final UiWidgetRegistrationRepository repo;
    private final EndpointManifestPublisher endpoints;
    private final ObjectMapper mapper;

    /** POST body of /internal/ui/widgets/register. */
    public record RegisterRequest(String bundle, String portal, List<Map<String, Object>> widgets) { }

    public record RegisterResult(String portal, String bundle, List<String> added, List<String> unchanged, List<String> retired) { }

    /** A rejected registration: every problem found, nothing stored. */
    public static class RejectedException extends RuntimeException {
        private final List<String> problems;
        public RejectedException(List<String> problems) {
            super(problems.size() + " problem(s)");
            this.problems = List.copyOf(problems);
        }
        public List<String> problems() { return problems; }
    }

    // ------------------------------------------------------------------ validation

    /** Problems with one registration, against this backend's endpoints (empty = acceptable). */
    public List<String> problems(RegisterRequest req) {
        List<String> p = new ArrayList<>();
        if (req == null) return List.of("empty body");
        if (req.bundle() == null || !BUNDLE.matcher(req.bundle()).matches()) p.add("bundle must look like <portal-build>@<version>");
        if (req.portal() == null || !PORTAL.matcher(req.portal()).matches()) p.add("portal must be a business portal (PLATFORM, SUPPLIER, …)");
        if (req.widgets() == null) { p.add("widgets is missing"); return p; }
        if (!p.isEmpty()) return p;

        List<Endpoint> all = endpoints.endpoints();
        Set<String> knownCells = new HashSet<>();
        Set<String> knownRows = new HashSet<>();
        for (Endpoint e : all) if (e.permission() != null) {
            knownCells.add(e.permission());
            knownRows.add(e.permission().substring(0, e.permission().indexOf(':')));
        }
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> w : req.widgets()) {
            String id = str(w.get("id"));
            String at = "widget " + (id == null ? "?" : id);
            if (id == null || !ID.matcher(id).matches()) { p.add(at + ": id must look like area.name"); continue; }
            if (!seen.add(id)) p.add(at + ": registered twice");
            if (str(w.get("version")) == null || !SEMVER.matcher(str(w.get("version"))).matches()) p.add(at + ": version must be semver");
            if (!strings(w.get("contexts")).contains(req.portal())) p.add(at + ": its contexts do not include " + req.portal());

            Set<String> cells = new LinkedHashSet<>(strings(w.get("cells")));
            if (cells.isEmpty()) p.add(at + ": declares no permission");
            for (String c : cells) {
                boolean ok = c.contains(":") ? knownCells.contains(c) || c.startsWith("nav.") : knownRows.contains(c);
                if (!ok) p.add(at + ": permission " + c + " is not in the backend's catalogue");
            }

            Set<String> names = new LinkedHashSet<>(strings(w.get("sources")));
            names.addAll(strings(w.get("commands")));
            Set<String> routed = new HashSet<>();
            for (Map<String, Object> r : maps(w.get("routes"))) {
                String name = str(r.get("name"));
                String method = str(r.get("method"));
                String path = str(r.get("path"));
                if (!req.portal().equals(str(r.get("portal")))) continue;
                routed.add(name);
                if (name == null || !names.contains(name)) { p.add(at + ": route for undeclared " + name); continue; }
                if (path == null || !path.startsWith("/api/") || path.contains("..") || path.startsWith("//")) {
                    p.add(at + ": " + name + " → " + path + " is not a relative /api/ path");
                    continue;
                }
                Endpoint e = match(all, method, path);
                if (e == null) { p.add(at + ": " + name + " → " + method + " " + path + " is not served by this backend"); continue; }
                if (e.isPublic()) continue;
                String cell = e.permission();
                String row = cell.substring(0, cell.indexOf(':'));
                if (!cells.contains(cell) && !cells.contains(row)) {
                    p.add(at + ": " + name + " → " + method + " " + path + " enforces " + cell + " (" + e.handler()
                            + "), which the widget does not declare in requires or actions");
                }
            }
            for (String n : names) if (!routed.contains(n)) p.add(at + ": " + n + " has no route for " + req.portal());
        }
        return p;
    }

    /** The endpoint serving METHOD path, path variables compared by position only. */
    static Endpoint match(List<Endpoint> all, String method, String path) {
        String want = PATH_VAR.matcher(path).replaceAll("{}");
        for (Endpoint e : all) {
            if (!(e.method().equals(method) || "ANY".equals(e.method()))) continue;
            if (PATH_VAR.matcher(e.path()).replaceAll("{}").equals(want)) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------ register / list

    @Transactional
    public RegisterResult register(RegisterRequest req) {
        List<String> problems = problems(req);
        if (!problems.isEmpty()) throw new RejectedException(problems);
        Instant now = Instant.now();
        Map<String, UiWidgetRegistration> active = new HashMap<>();
        for (UiWidgetRegistration r : repo.findByPortalAndStatusOrderByWidgetId(req.portal(), UiWidgetRegistration.ACTIVE)) {
            active.put(r.getWidgetId(), r);
        }
        List<String> added = new ArrayList<>(), unchanged = new ArrayList<>(), retired = new ArrayList<>();
        List<UiWidgetRegistration> save = new ArrayList<>();
        Set<String> incoming = new HashSet<>();
        for (Map<String, Object> w : req.widgets()) {
            String id = str(w.get("id"));
            incoming.add(id);
            String json = json(w);
            UiWidgetRegistration old = active.get(id);
            if (old != null && old.getVersion().equals(str(w.get("version"))) && old.getManifest().equals(json)) {
                old.setBundle(req.bundle()); // same widget, newer build carries it
                save.add(old);
                unchanged.add(id + "@" + old.getVersion());
                continue;
            }
            if (old != null) { retire(old, now); save.add(old); retired.add(id + "@" + old.getVersion()); }
            UiWidgetRegistration r = new UiWidgetRegistration();
            r.setId(UUID.randomUUID().toString());
            r.setBundle(req.bundle());
            r.setPortal(req.portal());
            r.setWidgetId(id);
            r.setVersion(str(w.get("version")));
            r.setManifest(json);
            r.setRegisteredAt(now);
            save.add(r);
            added.add(id + "@" + r.getVersion());
        }
        for (UiWidgetRegistration old : active.values()) {
            if (incoming.contains(old.getWidgetId())) continue;
            retire(old, now);
            save.add(old);
            retired.add(old.getWidgetId() + "@" + old.getVersion());
        }
        // retire before insert: the unique (portal, widget_id) WHERE ACTIVE index
        save.sort(Comparator.comparing((UiWidgetRegistration r) -> UiWidgetRegistration.ACTIVE.equals(r.getStatus())));
        repo.saveAllAndFlush(save);
        log.info("[ui-registry] {} {}: added {}, unchanged {}, retired {}", req.portal(), req.bundle(), added, unchanged, retired);
        return new RegisterResult(req.portal(), req.bundle(), added, unchanged, retired);
    }

    /** What the portal builder and the RBAC screen discover for a portal. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> active(String portal) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UiWidgetRegistration r : repo.findByPortalAndStatusOrderByWidgetId(portal, UiWidgetRegistration.ACTIVE)) {
            Map<String, Object> m = new LinkedHashMap<>(read(r.getManifest()));
            m.put("bundle", r.getBundle());
            m.put("registeredAt", r.getRegisteredAt().toString());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void retire(UiWidgetRegistration r, Instant now) {
        r.setStatus(UiWidgetRegistration.RETIRED);
        r.setRetiredAt(now);
    }

    private String json(Map<String, Object> w) {
        try { return mapper.writeValueAsString(new TreeMap<>(w)); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    private Map<String, Object> read(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException e) { return Map.of("error", "unreadable manifest"); }
    }

    private static String str(Object o) { return o instanceof String s && !s.isBlank() ? s : null; }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object x : l) if (x instanceof String s) out.add(s);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }
}
