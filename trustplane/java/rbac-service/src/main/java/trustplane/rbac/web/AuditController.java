package trustplane.rbac.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.CrudResource;
import trustplane.shared.Permission;

import java.util.List;
import java.util.Map;

/** Audit trail + health probe. */
@RestController
@lombok.RequiredArgsConstructor
@CrudResource(value = "rbac.audit", standard = false, extra = {"read"})
public class AuditController {

    private final RbacStore store;
    private final JdbcTemplate db;

    @Permission("rbac.audit:read")
    @PreAuthorize("hasAuthority('rbac.audit:read')")
    @GetMapping("/rbac/admin/audit")
    public List<Map<String, Object>> audit(
            Authentication auth,
            @RequestParam(required = false, defaultValue = "") String tenant,
            @RequestParam(required = false, defaultValue = "") String supplier,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        WebSupport.recheck(store, auth, "rbac.audit:read");
        return store.audit(tenant, supplier, limit);
    }

    @GetMapping("/healthz")
    @trustplane.shared.PublicEndpoint
    public ResponseEntity<String> health() {
        try {
            db.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            return ResponseEntity.status(503).body("db down");
        }
    }
}
