package trustplane.shared;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans every {@link CrudResource}-annotated controller and derives the full
 * permission catalog of the service (standard CRUD+approve per resource plus
 * declared extras). The catalog is then pushed to the RBAC service, which
 * upserts the permissions into its registry — RBAC automatically picks up
 * controller permissions, no hand-maintained catalog.
 *
 * Modes:
 *  - http  : catalog POSTed to RBAC's registry endpoint over mTLS (business services)
 *  - local : catalog handed to a local consumer (the RBAC service itself)
 */
public final class PermissionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PermissionRegistrar.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Perm(String id, String description) {}
    public record Catalog(String service, List<Perm> permissions) {}

    public interface Sink {
        void register(Catalog catalog) throws Exception;
    }

    private final ApplicationContext app;
    private final String service;
    private final Sink sink;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public PermissionRegistrar(ApplicationContext app, String service, Sink sink) {
        this.app = app;
        this.service = service;
        this.sink = sink;
    }

    /** Builds the catalog from controller annotations (deterministic order). */
    public Catalog scan() {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String name : app.getBeanDefinitionNames()) {
            Class<?> target;
            try {
                Object bean = app.getBean(name);
                target = AopUtils.getTargetClass(bean);
            } catch (Exception e) {
                continue; // not instantiable here; skip
            }
            CrudResource[] decls = target.getAnnotationsByType(CrudResource.class);
            if (decls.length == 0) continue;
            for (CrudResource d : decls) {
                List<String> actions = new ArrayList<>();
                if (d.standard()) actions.addAll(PermissionMatrix.STANDARD);
                for (String x : d.extra()) if (!actions.contains(x)) actions.add(x);
                for (String action : actions) {
                    String id = PermissionMatrix.id(d.value(), action);
                    ids.putIfAbsent(id, d.value().replaceAll("\\.", " ") + " " + action.replace(":", " "));
                }
            }
        }
        List<Perm> perms = new ArrayList<>();
        ids.forEach((id, desc) -> perms.add(new Perm(id, desc)));
        return new Catalog(service, perms);
    }

    /** Fails startup if any mapped endpoint method lacks its permission binding. */
    public void auditEndpoints() {
        RequestMappingHandlerMapping mapping = app.getBean(RequestMappingHandlerMapping.class);
        mapping.getHandlerMethods().forEach((info, hm) -> {
            Class<?> cls = hm.getBeanType();
            if (!cls.getName().startsWith("trustplane.")) return;
            Method m = hm.getMethod();
            CrudResource[] decls = cls.getAnnotationsByType(CrudResource.class);
            if (decls.length == 0) return; // framework/utility controllers
            if (m.isAnnotationPresent(PublicEndpoint.class)) return; // intentional public endpoint (e.g. health probe)
            if (!m.isAnnotationPresent(Permission.class)) {
                throw new IllegalStateException("endpoint without permission binding: "
                        + cls.getSimpleName() + "#" + m.getName() + " -> " + info);
            }
        });
        log.info("[permissions] endpoint audit passed for {}", service);
    }

    /** Registers the catalog, retrying until RBAC is available. */
    public void registerWithRetry(int maxAttempts) {
        Catalog catalog = scan();
        log.info("[permissions] catalog of {}: {} permissions", service, catalog.permissions().size());
        for (int i = 1; i <= maxAttempts && !registered.get(); i++) {
            try {
                sink.register(catalog);
                registered.set(true);
                log.info("[permissions] {} registered with RBAC ({} permissions)", service, catalog.permissions().size());
                return;
            } catch (Exception e) {
                log.warn("[permissions] registration attempt {}/{} failed: {}", i, maxAttempts, e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        if (!registered.get()) {
            throw new IllegalStateException("permission registration with RBAC failed after " + maxAttempts + " attempts");
        }
    }

    /** Sink posting the catalog to RBAC over mTLS (used by business services). */
    public static Sink httpSink(String registryUrl, String certPath, String keyPath, String caPath) {
        return catalog -> {
            String body = MAPPER.writeValueAsString(catalog);
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(PemKeyStore.clientContext(certPath, keyPath, caPath))
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(registryUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Mesh-Service", catalog.service())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("registry response " + resp.statusCode() + ": " + resp.body());
            }
        };
    }

    /** Helper for controllers: parse an incoming catalog registration body. */
    public static Catalog parseCatalog(String json) {
        try {
            var node = MAPPER.readTree(json);
            String service = node.path("service").asText("");
            List<Perm> perms = new ArrayList<>();
            for (var p : node.path("permissions")) {
                perms.add(new Perm(p.path("id").asText(""), p.path("description").asText("")));
            }
            return new Catalog(service, perms);
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed catalog: " + e.getMessage());
        }
    }

    public boolean isRegistered() { return registered.get(); }
}
