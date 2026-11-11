package trustplane.rbac.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import trustplane.rbac.web.RegistryService;
import trustplane.shared.PermissionRegistrar;

/**
 * Boot sequence step 2 (after schema+seed):
 *  1. fail-fast permission audit: every mapped endpoint under trustplane.*
 *     MUST carry its {@code @Permission} binding;
 *  2. self-registration: the RBAC service registers its OWN controller
 *     catalog (rbac.supplier:create, rbac.grant:delete, ...) — the same
 *     auto-pickup mechanism business services use, applied locally.
 */
@Component
@Order(2)
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class BootRunner implements ApplicationRunner {

    private final ApplicationContext app;
    private final RegistryService registry;

    @Override
    public void run(ApplicationArguments args) {
        PermissionRegistrar registrar = new PermissionRegistrar(app, "rbac", catalog ->
                registry.register(catalog.service(), catalog.permissions().stream()
                        .map(p -> {
                            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                            m.put("id", p.id());
                            m.put("description", p.description());
                            return m;
                        })
                        .toList()));
        registrar.auditEndpoints();
        registrar.registerWithRetry(3);
        log.info("[rbac] permission catalog self-registered ({} permissions)",
                registrar.scan().permissions().size());
    }
}
