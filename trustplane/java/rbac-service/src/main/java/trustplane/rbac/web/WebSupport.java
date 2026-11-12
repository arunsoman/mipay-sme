package trustplane.rbac.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import trustplane.rbac.core.Decisions;
import trustplane.shared.ApiException;
import trustplane.shared.Failure;
import trustplane.shared.GatewayContext;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Set;

/**
 * Cross-cutting web support:
 * <ul>
 *   <li>the actor of a gateway request is the core user id (uid) from the signed context;</li>
 *   <li>every admin endpoint re-checks its cell against the decision logic, with the real tenant
 *       (defence in depth: the gateway checked the same cell before routing);</li>
 *   <li>mesh endpoints identify the caller by its mTLS certificate CN;</li>
 *   <li>errors map to {"error","reason"}.</li>
 * </ul>
 */
@RestControllerAdvice
public class WebSupport {

    /** RBAC subject of the current gateway-authenticated request. */
    public static String actor(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof GatewayContext ctx && !ctx.subject().isBlank()) return ctx.subject();
        throw Failure.toException(new Failure.MissingContext());
    }

    /** The actor must hold the cell in the tenant ("" = in some tenant). */
    public static void require(Decisions d, Authentication auth, String tenant, String cell) {
        var dec = d.check(new Decisions.Request(actor(auth), tenant == null ? "" : tenant, cell));
        if (!dec.allowed()) {
            throw new ApiException(403, "insufficient_permission", "subject lacks " + cell
                    + (tenant == null || tenant.isBlank() ? "" : " in " + tenant) + " (" + dec.reason() + ", " + dec.decisionId() + ")");
        }
    }

    /** Cross-org reads: the cell through a platform-wide assignment, or the cell in the given tenant. */
    public static void requireTenantOrPlatform(Decisions d, Authentication auth, String tenant, String cell) {
        if (tenant != null && !tenant.isBlank()) {
            require(d, auth, tenant, cell);
            return;
        }
        if (!d.checkPlatform(actor(auth), cell)) {
            throw new ApiException(403, "insufficient_permission", "listing across orgs needs " + cell + " platform-wide; pass ?tenant=");
        }
    }

    public static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            throw ApiException.badRequest("timestamps are RFC 3339 (UTC), e.g. 2026-11-12T10:00:00Z");
        }
    }

    // ---- mesh identity ----------------------------------------------------

    static String clientCertCN(HttpServletRequest req) {
        Object attr = req.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attr instanceof Certificate[] certs) || certs.length == 0) return null;
        if (certs[0] instanceof X509Certificate x) {
            for (String part : x.getSubjectX500Principal().getName().split(",")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equalsIgnoreCase("CN")) return kv[1];
            }
        }
        return null;
    }

    public static String normalize(String s) {
        String v = s == null ? "" : s.trim().toLowerCase();
        if (v.startsWith("sme-")) v = v.substring(4);
        if (v.endsWith("-mesh")) v = v.substring(0, v.length() - 5);
        return v;
    }

    /** The mesh caller (normalised CN); its X-Mesh-Service header must agree. One of {@code allowed} if given. */
    public static String meshCaller(HttpServletRequest req, String header, Set<String> allowed) {
        String cn = clientCertCN(req);
        if (cn == null || header == null || header.isBlank() || !normalize(cn).equals(normalize(header))) {
            throw new ApiException(401, "unauthenticated", "mesh caller: X-Mesh-Service must match the client certificate CN");
        }
        String who = normalize(cn);
        if (allowed != null && !allowed.contains(who)) throw new ApiException(403, "forbidden", "mesh caller " + who + " may not use this endpoint");
        return who;
    }

    // ---- errors -----------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> apiError(ApiException e) {
        return ResponseEntity.status(e.status).header("Content-Type", "application/json").body(e.body);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<String> badBody(Exception e) {
        return ResponseEntity.status(400).header("Content-Type", "application/json").body("{\"error\":\"bad_request\",\"reason\":\"malformed JSON body\"}");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> generic(Exception e) {
        org.slf4j.LoggerFactory.getLogger(WebSupport.class).error("[rbac] unhandled", e);
        String msg = e.getMessage() == null ? "unexpected" : e.getMessage().replace("\"", "'").replace("\n", " ");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).header("Content-Type", "application/json")
                .body("{\"error\":\"internal\",\"reason\":\"" + msg + "\"}");
    }
}
