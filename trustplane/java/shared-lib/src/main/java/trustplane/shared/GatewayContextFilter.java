package trustplane.shared;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Verifies the API gateway's signed identity header on every inbound mesh
 * request and installs the resulting authentication into Spring Security.
 * Byte-for-byte compatible with internal/ctxauth:  header value is
 * "&lt;base64url(payloadjson)&gt;.&lt;base64url(hmac_sha256(payload-b64-string))&gt;".
 *
 * Requests without a valid header are rejected 401 — services in the mesh
 * never accept identity from anything but the gateway.
 */
public class GatewayContextFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-SME-Context";
    public static final String ACTOR_HEADER = "X-Actor";
    public static final String SUPPLIER_HEADER = "X-Supplier";

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] secret;
    private final long maxSkewSeconds;
    private final java.util.List<String> publicPrefixes;

    public GatewayContextFilter(byte[] secret, long maxSkewSeconds) {
        this(secret, maxSkewSeconds, new String[0]);
    }

    /**
     * @param publicPrefixes request paths (exact or prefix, e.g. "/healthz",
     *        "/rbac/internal/") served without a gateway context header.
     *        Used for liveness probes and mTLS-guarded internal endpoints;
     *        TLS-layer client-cert enforcement still applies.
     */
    public GatewayContextFilter(byte[] secret, long maxSkewSeconds, String... publicPrefixes) {
        this.secret = secret;
        this.maxSkewSeconds = maxSkewSeconds;
        this.publicPrefixes = java.util.List.of(publicPrefixes);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String uri = req.getRequestURI();
        for (String p : publicPrefixes) {
            if (uri.equals(p) || uri.startsWith(p.endsWith("/") ? p : p + "/")
                    || uri.equals(p.substring(0, p.length() - (p.endsWith("/") ? 1 : 0)))) {
                chain.doFilter(req, res);
                return;
            }
        }
        String v = req.getHeader(HEADER);
        if (v == null || v.isBlank()) {
            reject(res, "unauthenticated: missing gateway context");
            return;
        }
        String[] parts = v.split("\\.");
        if (parts.length != 2) {
            reject(res, "unauthenticated: malformed gateway context");
            return;
        }
        byte[] mac = hmac(secret, parts[0].getBytes(StandardCharsets.US_ASCII));
        if (!MessageDigest.isEqual(mac, B64.decode(parts[1]))) {
            reject(res, "unauthenticated: bad gateway context MAC");
            return;
        }
        GatewayContext ctx;
        try {
            ctx = GatewayContext.parse(new String(B64.decode(parts[0]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            reject(res, "unauthenticated: undecodable gateway context");
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ctx.ts()) > maxSkewSeconds) {
            reject(res, "unauthenticated: stale gateway context");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new GatewayContextAuthentication(ctx));
        req.setAttribute("trustplane.context", ctx);
        // defensive: strip any caller-provided actor echo headers
        req.removeAttribute(ACTOR_HEADER);
        chain.doFilter(req, res);
        SecurityContextHolder.clearContext();
    }

    private static void reject(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write(new ObjectMapper().writeValueAsString(java.util.Map.of("error", msg)));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
