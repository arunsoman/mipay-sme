package com.mipay.sme.security.rbac;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The core's mTLS client for RBAC's org-access mesh API (docs/plans/extended-rbac.md ER3):
 * an org's roles and ceiling, its org-defined roles, and role assignments of its users,
 * written on behalf of the acting core user. The base URL derives from
 * {@code APP_RBAC_REGISTRY_URL}, like the catalogue registration and the org-tree sync.
 */
@Component
@Slf4j
public class RbacMeshClient {

    /** RBAC answered with an error, or could not be reached; status and body are passed on. */
    public static class RbacMeshException extends RuntimeException {
        public final int status;
        public final String code;

        public RbacMeshException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private volatile HttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment env;

    public boolean configured() {
        return base() != null;
    }

    String base() {
        // env first (deployment wiring), then the app property (tests and single-process setups)
        String url = System.getenv("APP_RBAC_REGISTRY_URL");
        if (url == null || url.isBlank()) url = env.getProperty("app.rbac-url");
        if (url == null || url.isBlank()) return null;
        int i = url.indexOf("/rbac/");
        return i < 0 ? url.replaceAll("/+$", "") : url.substring(0, i);
    }

    public Map<String, Object> rolesFor(String tenant) {
        return call("GET", "/rbac/internal/tenants/" + enc(tenant) + "/roles", null, new TypeReference<>() { });
    }

    public Map<String, Object> createRole(String tenant, Map<String, Object> body) {
        return call("POST", "/rbac/internal/tenants/" + enc(tenant) + "/roles", body, new TypeReference<>() { });
    }

    public Map<String, Object> updateRole(String tenant, String name, Map<String, Object> body) {
        return call("PUT", "/rbac/internal/tenants/" + enc(tenant) + "/roles/" + enc(name), body, new TypeReference<>() { });
    }

    public Map<String, Object> deleteRole(String tenant, String name, Map<String, Object> body) {
        return call("POST", "/rbac/internal/tenants/" + enc(tenant) + "/roles/" + enc(name) + "/delete", body, new TypeReference<>() { });
    }

    public List<Map<String, Object>> assignments(String tenant) {
        return call("GET", "/rbac/internal/tenants/" + enc(tenant) + "/assignments", null, new TypeReference<>() { });
    }

    /** A user's assignments across all tenants (reconcile, backfill, 2FA profile). */
    public List<Map<String, Object>> assignmentsFor(String subject) {
        return call("GET", "/rbac/internal/assignments?subject=" + enc(subject), null, new TypeReference<>() { });
    }

    /** Direct entitlement grant on one tenant (onboarding rights, P3.11 backfill). */
    public Map<String, Object> grant(String tenant, String permission, List<String> allowedValues,
                                     String scope, boolean canDelegate) {
        return call("POST", "/rbac/internal/grants", Map.of(
                "tenant", tenant, "permission", permission,
                "allowed_values", allowedValues == null ? List.of() : allowedValues,
                "scope", scope, "can_delegate", canDelegate), new TypeReference<>() { });
    }

    public Map<String, Object> assign(String tenant, Map<String, Object> body) {
        return call("POST", "/rbac/internal/tenants/" + enc(tenant) + "/assignments", body, new TypeReference<>() { });
    }

    /** A system write decided by the core (backfill, reconcile): no actor, source names the process. */
    public Map<String, Object> assignSystem(String subject, String role, String tenant, String scope, String reason) {
        return call("POST", "/rbac/internal/assignments", Map.of(
                "subject", subject, "role", role, "tenant", tenant, "scope", scope,
                "reason", reason == null ? "" : reason, "source", "backfill"), new TypeReference<>() { });
    }

    public Map<String, Object> revoke(String tenant, String id, Map<String, Object> body) {
        return call("POST", "/rbac/internal/tenants/" + enc(tenant) + "/assignments/" + enc(id) + "/revoke", body, new TypeReference<>() { });
    }

    /** Revokes every grant and assignment in the tenant (org rejection/closure, §3.3c). */
    public Map<String, Object> revokeAll(String tenant, String reason) {
        return call("POST", "/rbac/internal/tenants/" + enc(tenant) + "/revoke-all",
                Map.of("reason", reason == null ? "org archived" : reason), new TypeReference<>() { });
    }

    public List<Map<String, Object>> approvals(String tenant, String actor) {
        return call("GET", "/rbac/internal/tenants/" + enc(tenant) + "/approvals?actor=" + enc(actor), null, new TypeReference<>() { });
    }

    public Map<String, Object> decide(String tenant, String id, Map<String, Object> body) {
        return call("POST", "/rbac/internal/tenants/" + enc(tenant) + "/approvals/" + enc(id), body, new TypeReference<>() { });
    }

    // ---- platform-wide (admin portal; cut-over P5.5) ----

    public List<Map<String, Object>> platformRoles() {
        return call("GET", "/rbac/internal/platform/roles", null, new TypeReference<>() { });
    }

    public List<Map<String, Object>> platformResources() {
        return call("GET", "/rbac/internal/platform/resources", null, new TypeReference<>() { });
    }

    public Map<String, Object> updatePlatformRole(String name, Map<String, Object> body) {
        return call("PUT", "/rbac/internal/platform/roles/" + enc(name), body, new TypeReference<>() { });
    }

    public List<Map<String, Object>> platformAssignments(String status) {
        return call("GET", "/rbac/internal/platform/assignments?status=" + enc(status), null, new TypeReference<>() { });
    }

    public Map<String, Object> platformAssign(Map<String, Object> body) {
        return call("POST", "/rbac/internal/platform/assignments", body, new TypeReference<>() { });
    }

    public Map<String, Object> platformRevoke(String id, Map<String, Object> body) {
        return call("POST", "/rbac/internal/platform/assignments/" + enc(id) + "/revoke", body, new TypeReference<>() { });
    }

    // ------------------------------------------------------------------

    private HttpClient http() throws Exception {
        HttpClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = HttpClient.newBuilder().sslContext(MeshTls.clientContext()).connectTimeout(Duration.ofSeconds(5)).build();
                }
                c = client;
            }
        }
        return c;
    }

    private <T> T call(String method, String path, Object body, TypeReference<T> type) {
        String base = base();
        if (base == null) throw new RbacMeshException(503, "RBAC_UNAVAILABLE", "RBAC is not configured (APP_RBAC_REGISTRY_URL)");
        HttpResponse<String> resp;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-Mesh-Service", "mipay")
                    .header("Content-Type", "application/json");
            HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            b.method(method, pub);
            resp = http().send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[rbac-mesh] {} {} failed: {}", method, path, e.getMessage());
            throw new RbacMeshException(503, "RBAC_UNAVAILABLE", "RBAC could not be reached");
        }
        if (resp.statusCode() / 100 != 2) {
            String code = "RBAC_REFUSED", reason = resp.body();
            try {
                Map<String, Object> err = JSON.readValue(resp.body(), new TypeReference<>() { });
                if (err.get("error") != null) code = String.valueOf(err.get("error"));
                if (err.get("reason") != null) reason = String.valueOf(err.get("reason"));
            } catch (Exception ignore) { }
            throw new RbacMeshException(resp.statusCode() >= 500 ? 502 : resp.statusCode(), code, reason);
        }
        try {
            return JSON.readValue(resp.body(), type);
        } catch (Exception e) {
            throw new RbacMeshException(502, "RBAC_BAD_RESPONSE", "unreadable RBAC response");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
