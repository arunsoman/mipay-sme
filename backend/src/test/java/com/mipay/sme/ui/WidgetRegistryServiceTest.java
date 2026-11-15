package com.mipay.sme.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.domain.ui.UiWidgetRegistration;
import com.mipay.sme.repository.UiWidgetRegistrationRepository;
import com.mipay.sme.security.rbac.EndpointManifestPublisher;
import com.mipay.sme.security.rbac.EndpointManifestPublisher.Endpoint;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.PublicEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The UI widget registry (docs/plans/portal-builder.md §7). The central test registers the REAL
 * generated frontend manifest (frontend/projects/shared/widgets/widget-manifest.json, written
 * by tools/widgets.ts gen) against the REAL @Perm annotations of the backend controllers - the
 * same check the server runs at deploy, so a widget that calls an endpoint it does not declare
 * fails here first.
 */
class WidgetRegistryServiceTest {

    private static final Path MANIFEST = Path.of("../frontend/projects/shared/widgets/widget-manifest.json");
    private final ObjectMapper mapper = new ObjectMapper();
    private final UiWidgetRegistrationRepository repo = mock(UiWidgetRegistrationRepository.class);
    private final EndpointManifestPublisher publisher = mock(EndpointManifestPublisher.class);
    private final List<UiWidgetRegistration> rows = new ArrayList<>();
    private WidgetRegistryService service;

    @BeforeEach
    void setUp() {
        when(publisher.endpoints()).thenReturn(realEndpoints());
        when(repo.findByPortalAndStatusOrderByWidgetId(any(), any())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getPortal().equals(inv.getArgument(0)) && r.getStatus().equals(inv.getArgument(1)))
                .sorted(Comparator.comparing(UiWidgetRegistration::getWidgetId)).toList());
        when(repo.saveAllAndFlush(anyList())).thenAnswer(inv -> {
            List<UiWidgetRegistration> in = inv.getArgument(0);
            for (UiWidgetRegistration r : in) if (!rows.contains(r)) rows.add(r);
            return in;
        });
        service = new WidgetRegistryService(repo, publisher, mapper);
    }

    // ---------------------------------------------------------------- the real manifest

    @Test
    void theGeneratedFrontendManifestPassesAgainstTheRealEndpointsInEveryPortal() throws Exception {
        List<Map<String, Object>> widgets = manifestWidgets();
        assertFalse(widgets.isEmpty(), "widget-manifest.json lists widgets (npm run widgets:gen)");
        // widgets:apply checks one widget at a time (WIDGET_ONLY); CI checks them all
        String only = System.getenv("WIDGET_ONLY");
        if (only != null && !only.isBlank()) widgets = widgets.stream().filter(w -> only.equals(w.get("id"))).toList();
        assertFalse(widgets.isEmpty(), "no widget " + only + " in widget-manifest.json");
        for (Map<String, Object> w : widgets) {
            for (String portal : strings(w.get("contexts"))) {
                List<String> problems = service.problems(request(portal, w));
                assertEquals(List.of(), problems, w.get("id") + " in " + portal);
            }
        }
    }

    @Test
    void kycApplicationIsDiscoverableAfterRegistration() throws Exception {
        Map<String, Object> kyc = widget("kyc.application");
        WidgetRegistryService.RegisterResult r = service.register(request("SUPPLIER", kyc));
        assertEquals(List.of("kyc.application@1.0.0"), r.added());

        List<Map<String, Object>> found = service.active("SUPPLIER");
        assertEquals(1, found.size());
        assertEquals("kyc.application", found.get(0).get("id"));
        assertEquals("KYC application", found.get(0).get("label"));
        assertEquals("supplier-portal@test1", found.get(0).get("bundle"));
        assertTrue(strings(found.get(0).get("cells")).contains("kyc.application:submit"));
        assertTrue(service.active("MERCHANT").isEmpty(), "registered per portal");
    }

    // ---------------------------------------------------------------- coherence (decision 6)

    @Test
    void refusesAWidgetThatCallsAnEndpointWhosePermissionItDoesNotDeclare() throws Exception {
        Map<String, Object> kyc = new LinkedHashMap<>(widget("kyc.application"));
        kyc.put("cells", strings(kyc.get("cells")).stream().filter(c -> !c.equals("kyc.form:read")).toList());
        List<String> p = service.problems(request("SUPPLIER", kyc));
        assertTrue(p.stream().anyMatch(x -> x.contains("kyc.schema.get") && x.contains("enforces kyc.form:read")), p.toString());
        assertThrows(WidgetRegistryService.RejectedException.class, () -> service.register(request("SUPPLIER", kyc)));
        assertTrue(rows.isEmpty(), "nothing stored on rejection");
    }

    @Test
    void refusesRoutesThisBackendDoesNotServeAndUnknownPermissions() throws Exception {
        Map<String, Object> kyc = new LinkedHashMap<>(widget("kyc.application"));
        List<Map<String, Object>> routes = new ArrayList<>(maps(kyc.get("routes")));
        routes.add(Map.of("name", "kyc.application.get", "kind", "source", "portal", "SUPPLIER", "method", "GET", "path", "/api/kyc/nowhere"));
        kyc.put("routes", routes);
        List<String> cells = new ArrayList<>(strings(kyc.get("cells")));
        cells.add("kyc.application:explode");
        kyc.put("cells", cells);
        List<String> p = service.problems(request("SUPPLIER", kyc));
        assertTrue(p.stream().anyMatch(x -> x.contains("/api/kyc/nowhere is not served")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("kyc.application:explode is not in the backend's catalogue")), p.toString());
    }

    @Test
    void refusesAWidgetOutsideItsContextsAndBadBundles() throws Exception {
        Map<String, Object> kyc = widget("kyc.application");
        assertTrue(service.problems(request("MERCHANT", kyc)).stream().anyMatch(x -> x.contains("contexts do not include MERCHANT")));
        assertTrue(service.problems(new WidgetRegistryService.RegisterRequest("no version", "SUPPLIER", List.of()))
                .stream().anyMatch(x -> x.contains("bundle")));
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void aNewBuildKeepsUnchangedWidgetsAndRetiresTheOnesItNoLongerCarries() throws Exception {
        Map<String, Object> kyc = widget("kyc.application");
        service.register(request("SUPPLIER", kyc));

        WidgetRegistryService.RegisterResult same = service.register(
                new WidgetRegistryService.RegisterRequest("supplier-portal@test2", "SUPPLIER", List.of(routesFor("SUPPLIER", kyc))));
        assertEquals(List.of("kyc.application@1.0.0"), same.unchanged());
        assertEquals("supplier-portal@test2", service.active("SUPPLIER").get(0).get("bundle"));

        WidgetRegistryService.RegisterResult gone = service.register(
                new WidgetRegistryService.RegisterRequest("supplier-portal@test3", "SUPPLIER", List.of()));
        assertEquals(List.of("kyc.application@1.0.0"), gone.retired());
        assertTrue(service.active("SUPPLIER").isEmpty());
        assertEquals(1, rows.size(), "retired, not deleted");
        assertEquals(UiWidgetRegistration.RETIRED, rows.get(0).getStatus());
        assertNotNull(rows.get(0).getRetiredAt());
    }

    @Test
    void aNewVersionRetiresTheOldOne() throws Exception {
        Map<String, Object> kyc = widget("kyc.application");
        service.register(request("SUPPLIER", kyc));
        Map<String, Object> v2 = new LinkedHashMap<>(routesFor("SUPPLIER", kyc));
        v2.put("version", "1.1.0");
        WidgetRegistryService.RegisterResult r = service.register(
                new WidgetRegistryService.RegisterRequest("supplier-portal@test4", "SUPPLIER", List.of(v2)));
        assertEquals(List.of("kyc.application@1.1.0"), r.added());
        assertEquals(List.of("kyc.application@1.0.0"), r.retired());
        assertEquals("1.1.0", service.active("SUPPLIER").get(0).get("version"));
    }

    // ---------------------------------------------------------------- the internal endpoint

    @Test
    void registrationNeedsTheDeployToken() throws Exception {
        WidgetRegistryInternalController c = new WidgetRegistryInternalController(service);
        WidgetRegistryService.RegisterRequest req = request("SUPPLIER", widget("kyc.application"));

        ReflectionTestUtils.setField(c, "token", "");
        assertEquals(404, c.register("anything", req).getStatusCode().value(), "no token configured: switched off");

        ReflectionTestUtils.setField(c, "token", "deploy-secret");
        assertEquals(401, c.register(null, req).getStatusCode().value());
        assertEquals(401, c.register("wrong", req).getStatusCode().value());
        ResponseEntity<Object> ok = c.register("deploy-secret", req);
        assertEquals(200, ok.getStatusCode().value());

        Map<String, Object> bad = new LinkedHashMap<>(widget("kyc.application"));
        bad.put("cells", List.of("kyc.application:read"));
        ResponseEntity<Object> refused = c.register("deploy-secret", request("SUPPLIER", bad));
        assertEquals(422, refused.getStatusCode().value());
        verify(repo, times(1)).saveAllAndFlush(anyList());
    }

    // ---------------------------------------------------------------- helpers

    /** What tools/widgets.ts register sends for one portal: the widget with that portal's routes. */
    private WidgetRegistryService.RegisterRequest request(String portal, Map<String, Object> w) {
        return new WidgetRegistryService.RegisterRequest(portal.toLowerCase() + "-portal@test1", portal, List.of(routesFor(portal, w)));
    }

    private Map<String, Object> routesFor(String portal, Map<String, Object> w) {
        Map<String, Object> out = new LinkedHashMap<>(w);
        out.put("routes", maps(w.get("routes")).stream().filter(r -> portal.equals(r.get("portal"))).toList());
        return out;
    }

    private List<Map<String, Object>> manifestWidgets() throws Exception {
        assertTrue(Files.exists(MANIFEST), "run npm run widgets:gen in frontend/ first: " + MANIFEST.toAbsolutePath());
        Map<String, Object> m = mapper.readValue(Files.readString(MANIFEST), new TypeReference<>() { });
        return maps(m.get("widgets"));
    }

    private Map<String, Object> widget(String id) throws Exception {
        return manifestWidgets().stream().filter(w -> id.equals(w.get("id"))).findFirst().orElseThrow();
    }

    /**
     * The endpoint manifest of the real controllers, built from their annotations exactly as
     * EndpointManifestPublisher sees them (class + method @RequestMapping, @Perm / @PublicEndpoint).
     */
    static List<Endpoint> realEndpoints() {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Endpoint> out = new ArrayList<>();
        for (var bd : scan.findCandidateComponents("com.mipay.sme.web")) {
            Class<?> type;
            try { type = Class.forName(bd.getBeanClassName()); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[]{""} : base.path();
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                Perm perm = m.getAnnotation(Perm.class);
                PublicEndpoint pub = m.getAnnotation(PublicEndpoint.class);
                String[] paths = rm.path().length == 0 ? new String[]{""} : rm.path();
                for (String prefix : prefixes) for (String p : paths) for (var verb : rm.method()) {
                    String path = prefix + p;
                    String handler = type.getSimpleName() + "#" + m.getName();
                    if (perm != null) out.add(new Endpoint(verb.name(), path, perm.resource() + ":" + perm.verb().id(),
                            perm.tenantSource().name(), perm.lookup(), false, null, handler));
                    else if (pub != null) out.add(new Endpoint(verb.name(), path, null, null, null, true, pub.reason(), handler));
                }
            }
        }
        return out;
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        return o instanceof List<?> l ? l.stream().map(x -> (Map<String, Object>) x).toList() : List.of();
    }
}
