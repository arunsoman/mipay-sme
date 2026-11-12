package trustplane.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Builds a service's grid catalogue from its handlers (fapi2-design.md Appendix F/G): every
 * {@link Permission} cell with the endpoints it protects, plus the public endpoints. The
 * catalogue has the same shape the MiPay backend publishes, so RBAC handles both alike:
 * {@code {service, permissions:[{id, description}], endpoints:[{method, path, permission, ...}]}}.
 */
public final class PermissionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PermissionRegistrar.class);
    /** {@code <service>.<resource>:<verb>} with a grid verb. */
    public static final Pattern CELL = Pattern.compile(
            "^[a-z]+\\.[a-z_]+:(read|create|modify|delete|submit|authorize|execute|export|administer|simulate)$");

    public record Perm(String id, String description) {}
    public record Endpoint(String method, String path, String permission, String tenantSource,
                           boolean isPublic, String reason, String handler) {}
    public record Catalog(String service, List<Perm> permissions, List<Endpoint> endpoints) {}

    private final ApplicationContext app;
    private final String service;

    public PermissionRegistrar(ApplicationContext app, String service) {
        this.app = app;
        this.service = service;
    }

    /**
     * Scans every trustplane handler. Fails if one has neither {@link Permission} nor
     * {@link PublicEndpoint}, or names a cell that is not {@code row:verb} with a grid verb.
     */
    public Catalog scan() {
        RequestMappingHandlerMapping mapping = app.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Map<String, String> cells = new TreeMap<>();
        List<Endpoint> endpoints = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = e.getValue();
            if (!hm.getBeanType().getName().startsWith("trustplane.")) continue;
            String handler = hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
            Permission perm = hm.getMethodAnnotation(Permission.class);
            PublicEndpoint pub = hm.getMethodAnnotation(PublicEndpoint.class);
            Set<String> methods = new TreeSet<>();
            e.getKey().getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            for (String path : e.getKey().getPatternValues()) {
                for (String method : methods.isEmpty() ? Set.of("ANY") : methods) {
                    if (perm != null) {
                        if (!CELL.matcher(perm.value()).matches()) {
                            throw new IllegalStateException("bad grid cell " + perm.value() + " on " + handler);
                        }
                        cells.putIfAbsent(perm.value(), perm.description().isBlank()
                                ? perm.value().replace('.', ' ').replace(':', ' ') : perm.description());
                        endpoints.add(new Endpoint(method, path, perm.value(), perm.tenantSource(), false, null, handler));
                    } else if (pub != null) {
                        endpoints.add(new Endpoint(method, path, null, null, true, pub.value(), handler));
                    } else {
                        missing.add(method + " " + path + " (" + handler + ")");
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("endpoints without @Permission or @PublicEndpoint: " + missing);
        }
        List<Perm> perms = new ArrayList<>();
        cells.forEach((id, d) -> perms.add(new Perm(id, d)));
        log.info("[permissions] {}: {} cells over {} endpoints", service, perms.size(), endpoints.size());
        return new Catalog(service, perms, endpoints);
    }
}
