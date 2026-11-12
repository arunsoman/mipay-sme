package com.mipay.sme.security.rbac;

import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.security.Current;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The method-security decision behind {@link Perm} ({@code @rbacAuthz.check(...)}), cut-over P6 / OA8.
 *
 * Modes ({@code app.rbac.mode}):
 * <ul>
 *   <li>{@code enforce} - RBAC decides: a call runs only when RBAC allows the cell. The legacy
 *       checks (URL roles, service rules) still run as well, so both must allow. Fails closed
 *       when RBAC cannot be reached;</li>
 *   <li>{@code shadow} (default) - never blocks; when RBAC is configured it asks anyway and logs
 *       every call RBAC would refuse ({@code [rbac-shadow-deny]});</li>
 *   <li>{@code off} - never blocks, never asks.</li>
 * </ul>
 *
 * The tenant RBAC is asked about: PATH = the path value when it is an org id; SELF = the
 * caller's org; NONE, LOOKUP and a PATH value that is not an org (a user, task or request id)
 * = "in any tenant" - the service's object-level checks then decide which object.
 * Rollback: {@code app.rbac.mode=shadow}.
 */
@Component("rbacAuthz")
@Slf4j
public class RbacAuthz {

    private static final long TTL_MS = 5_000;

    @Value("${app.rbac.mode:shadow}")
    private String mode;

    private final RbacMeshClient rbac;
    private final OrganizationRepository orgs;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(boolean allowed, long at) { }

    public RbacAuthz(RbacMeshClient rbac, OrganizationRepository orgs) {
        this.rbac = rbac;
        this.orgs = orgs;
    }

    @PostConstruct
    void validateMode() {
        if (!mode.equals("shadow") && !mode.equals("off") && !mode.equals("enforce")) {
            throw new IllegalStateException("app.rbac.mode=" + mode + " (shadow | enforce | off)");
        }
        if (mode.equals("enforce") && !rbac.configured()) {
            throw new IllegalStateException("app.rbac.mode=enforce needs RBAC (APP_RBAC_REGISTRY_URL)");
        }
        log.info("[rbac] method security in {} mode", mode);
    }

    public String mode() {
        return mode;
    }

    public boolean check(String resource, String verb, String tenantSource, Object tenant) {
        if (mode.equals("off") || (mode.equals("shadow") && !rbac.configured())) return true;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof Current.Principal p)) {
            // @Perm endpoints sit behind authentication; no principal means nothing to decide on
            return !mode.equals("enforce");
        }
        String operation = resource + ":" + verb.toLowerCase();
        String tenantId = tenantOf(tenantSource, tenant, p);
        boolean allowed = decide(p.userId(), tenantId, operation);
        if (!allowed) {
            log.warn("[rbac-{}] sub={} perm={} tenant={}", mode.equals("enforce") ? "deny" : "shadow-deny",
                    p.userId(), operation, tenantId.isEmpty() ? "*" : tenantId);
        }
        return allowed || !mode.equals("enforce");
    }

    String tenantOf(String tenantSource, Object tenant, Current.Principal p) {
        return switch (tenantSource) {
            case "SELF" -> p.orgId() == null ? "" : p.orgId();
            case "PATH" -> tenant != null && orgs.existsById(String.valueOf(tenant)) ? String.valueOf(tenant) : "";
            default -> "";
        };
    }

    private boolean decide(String subject, String tenant, String operation) {
        String key = subject + "|" + tenant + "|" + operation;
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && now - c.at() < TTL_MS) return c.allowed();
        boolean allowed;
        try {
            Map<String, Object> d = rbac.check(subject, tenant, operation);
            String reason = String.valueOf(d.get("reason"));
            // entitlement cells: the role allows it; which child type is a grant checked by the service
            allowed = Boolean.TRUE.equals(d.get("allowed")) || reason.startsWith("attribute_required:");
            if (!allowed) log.debug("[rbac] {} {} {} -> {}", subject, tenant, operation, reason);
        } catch (RbacMeshClient.RbacMeshException e) {
            log.error("[rbac] decision unavailable for {} {}: {} ({})", subject, operation, e.code, e.getMessage());
            return false; // fail closed, never cached
        }
        if (cache.size() > 50_000) cache.clear();
        cache.put(key, new Cached(allowed, now));
        return allowed;
    }
}
