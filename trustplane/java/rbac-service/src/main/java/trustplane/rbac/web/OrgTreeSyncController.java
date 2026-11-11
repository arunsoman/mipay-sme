package trustplane.rbac.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.rbac.web.WebSupport;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Org-tree synchronization endpoint for MiPay backend.
 *
 * MiPay pushes its org closure table to RBAC so that permission inheritance
 * can be evaluated across the org hierarchy (docs/plans/fapi2-design.md
 * §9.2). Every tenant in a manufacturer org has an RBAC permission set that
 * is a subset of what its parent org has — permissions flow DOWN the org tree.
 *
 * Identity: mTLS client cert + X-Mesh-Service header match (same as
 * RegistryController).
 */
@RestController
@lombok.RequiredArgsConstructor
public class OrgTreeSyncController {

    private final RbacStore store;

    public record SyncBody(List<Map<String, Object>> closure, List<Map<String, Object>> tenantMapping) {}

    @PostMapping("/rbac/internal/orgs/sync")
    public Map<String, Object> sync(@RequestBody SyncBody body,
                                    @org.springframework.web.bind.annotation.RequestHeader(value = "X-Mesh-Service", defaultValue = "") String meshService,
                                    HttpServletRequest req) {
        if (meshService.isBlank()) {
            throw new ApiException(401, "unauthenticated", "X-Mesh-Service required");
        }
        String cn = clientCertCN(req);
        if (cn == null || !normalize(cn).equals(normalize(meshService))) {
            throw new ApiException(401, "unauthenticated", "mesh service header does not match client certificate CN");
        }

        // Replace the closure table atomically
        if (body.closure() != null) {
            store.replaceOrgClosure(body.closure());
        }
        if (body.tenantMapping() != null) {
            store.replaceOrgTenantMapping(body.tenantMapping());
        }

        // Bump versions for all affected tenants so the gateway purges caches
        if (body.closure() != null) {
            for (var row : body.closure()) {
                store.touchWithDescendants(String.valueOf(row.get("ancestor")), List.of());
                store.touchWithDescendants(String.valueOf(row.get("descendant")), List.of());
            }
        }
        if (body.tenantMapping() != null) {
            for (var row : body.tenantMapping()) {
                store.touch(String.valueOf(row.get("tenant")), List.of());
            }
        }

        return Map.of("ok", true,
                "closureRows", body.closure() == null ? 0 : body.closure().size(),
                "tenantMappingRows", body.tenantMapping() == null ? 0 : body.tenantMapping().size());
    }

    private static String clientCertCN(HttpServletRequest req) {
        Object attr = req.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attr instanceof Certificate[] certs) || certs.length == 0) return null;
        if (certs[0] instanceof X509Certificate x) {
            String dn = x.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equalsIgnoreCase("CN")) return kv[1];
            }
        }
        return null;
    }

    private static String normalize(String s) {
        String v = s.trim().toLowerCase();
        if (v.startsWith("sme-")) v = v.substring(4);
        if (v.endsWith("-mesh")) v = v.substring(0, v.length() - 5);
        return v;
    }
}
