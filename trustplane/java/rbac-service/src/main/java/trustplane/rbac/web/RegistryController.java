package trustplane.rbac.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.shared.CrudResource;
import trustplane.shared.GatewayContext;
import trustplane.shared.Permission;
import trustplane.shared.PermissionRegistrar;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mesh internal registry: services push their controller-derived permission
 * catalogs here at boot (RBAC "automatically picks up" permissions).
 *
 * Identity: the request must arrive with a platform-CA client certificate
 * (TLS-layer mTLS) AND an X-Mesh-Service header whose value matches the
 * certificate CN (normalized), preventing catalog spoofing between services.
 */
@RestController
@lombok.RequiredArgsConstructor
public class RegistryController {

    private final RegistryService registry;

    @PostMapping("/rbac/internal/permissions/register")
    public Map<String, Object> register(@RequestBody(required = false) String body,
                                        @org.springframework.web.bind.annotation.RequestHeader(value = "X-Mesh-Service", defaultValue = "") String meshService,
                                        HttpServletRequest req) {
        if (meshService.isBlank()) {
            throw new ApiException(401, "unauthenticated", "X-Mesh-Service required");
        }
        String cn = clientCertCN(req);
        if (cn == null || !normalize(cn).equals(normalize(meshService))) {
            throw new ApiException(401, "unauthenticated", "mesh service header does not match client certificate CN");
        }
        PermissionRegistrar.Catalog catalog = PermissionRegistrar.parseCatalog(body == null ? "{}" : body);
        int registered = registry.register(catalog.service(), catalog.permissions().stream()
                .map(p -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("id", p.id());
                    m.put("description", p.description());
                    return m;
                })
                .toList());
        return Map.of("service", catalog.service(), "registered", registered);
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
