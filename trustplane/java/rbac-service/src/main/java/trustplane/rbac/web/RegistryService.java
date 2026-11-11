package trustplane.rbac.web;

import trustplane.rbac.core.RbacStore;
import trustplane.shared.Failure;
import trustplane.shared.ApiException;

import java.util.Map;

/**
 * Registry service: the auto-pickup point for controller permission
 * catalogs. Registers a catalog (from the caller's controller annotations)
 * into the permissions table and audits the registration. The RBAC service
 * uses the same path internally to register its own admin-plane catalog.
 */
@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class RegistryService {

    private final RbacStore store;

    private static ApiException fail(Failure f) {
        return Failure.toException(f);
    }

    /** Registers a parsed catalog; returns the number of new permissions. */
    public int register(String service, java.util.List<Map<String, String>> permissions) {
        if (service == null || service.isBlank()) {
            throw fail(new Failure.BadRequest("service required"));
        }
        if (permissions == null || permissions.isEmpty()) {
            throw fail(new Failure.BadRequest("catalog empty"));
        }
        return store.registerCatalog(service, permissions);
    }
}
