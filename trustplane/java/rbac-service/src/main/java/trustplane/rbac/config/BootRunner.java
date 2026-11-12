package trustplane.rbac.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.PermissionRegistrar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boot step 2 (after schema and seed): every RBAC admin endpoint must bind a grid cell
 * (startup fails otherwise), and RBAC registers its own cells with their endpoints the same
 * way business services do over the mesh.
 */
@Component
@Order(2)
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class BootRunner implements ApplicationRunner {

    private final ApplicationContext app;
    private final RbacStore store;

    @Override
    public void run(ApplicationArguments args) {
        PermissionRegistrar.Catalog c = new PermissionRegistrar(app, "rbac").scan();
        List<Map<String, String>> perms = c.permissions().stream().map(p -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("description", p.description());
            return m;
        }).toList();
        List<RbacStore.EndpointReg> eps = c.endpoints().stream().filter(e -> !e.isPublic())
                .map(e -> new RbacStore.EndpointReg(e.method(), e.path(), e.permission(), e.tenantSource(), e.handler())).toList();
        Map<String, Object> r = store.registerCatalog("rbac", perms, eps);
        log.info("[rbac] own catalogue registered: {}", r);
    }
}
