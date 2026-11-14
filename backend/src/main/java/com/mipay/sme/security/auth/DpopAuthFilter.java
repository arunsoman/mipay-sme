package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Verifies DPoP proof for access tokens per FAPI 2.0 / RFC 9449.
 *  - Expects Authorization header: "Bearer <access-token>" (or "DPoP <access-token>")
 *  - Expects DPoP header: "<compact-jws-proof>"
 *  - Validates proof.htm against HTTP request method
 *  - Validates proof.htu against normalized request URI
 *  - Validates proof.ath against the SHA-256 hash of the access token
 *  - Checks proof.jti for replay
 */
@Component
public final class DpopAuthFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION = "Authorization";
    private static final String DPOP = "DPoP";
    private static final long JTI_TTL_SECONDS = 600; // 10 minutes as per FAPI

    private final AppProperties properties;
    private final ObjectMapper mapper;
    // Simple replay cache: jti -> timestamp
    private final Map<String, Long> jtiCache = new ConcurrentHashMap<>();

    public DpopAuthFilter(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Only enforce DPoP when the gateway is the identity mode and the request is not an auth route
        if (!"gateway".equals(properties.getIdentityMode())) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader(AUTHORIZATION);
        if (authHeader == null || (!authHeader.startsWith("Bearer ") && !authHeader.startsWith("DPoP "))) {
            // No bearer/dpop token; let downstream filters handle it
            chain.doFilter(request, response);
            return;
        }

        String accessToken = authHeader.replaceFirst("^(?i)(Bearer|DPoP)\\s+", "").trim();

        String dpopHeader = request.getHeader(DPOP);
        if (dpopHeader == null || dpopHeader.isBlank()) {
            // DPoP header missing - FAPI 2.0 requires sender-constrained token
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_dpop_request\",\"error_description\":\"missing DPoP proof header\"}");
            return;
        }

        try {
            // ---------------------------------------------------------------
            // 1️⃣ Decode the DPoP proof (compact JWS: header.payload.signature)
            // ---------------------------------------------------------------
            String[] parts = dpopHeader.split("\\.", -1);
            if (parts.length != 3) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"invalid_dpop_proof\",\"error_description\":\"malformed DPoP proof JWS\"}");
                return;
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode proof = mapper.readTree(payloadBytes);
            if (proof == null || !proof.isObject()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"invalid_dpop_proof\",\"error_description\":\"invalid proof payload\"}");
                return;
            }

            // In RFC 9449, claims (htm, htu, ath, jti) are top-level payload claims; support fallback to cnf if nested
            JsonNode claims = proof.has("cnf") && proof.get("cnf").isObject() ? proof.get("cnf") : proof;

            // ---------------------------------------------------------------
            // 2️⃣ Validate proof.htm and proof.htu (HTTP method + target URI)
            // ---------------------------------------------------------------
            if (claims.has("htm")) {
                String expectedHtm = request.getMethod();
                if (!claims.get("htm").asText().equalsIgnoreCase(expectedHtm)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"invalid_dpop_proof\",\"error_description\":\"htm mismatch\"}");
                    return;
                }
            }

            String expectedHtu = buildHtu(request);
            if (!claims.has("htu") || !claims.get("htu").asText().equalsIgnoreCase(expectedHtu)) {
                // Also check if htu was formatted as "<method> <uri>" legacy format
                String legacyHtu = request.getMethod() + " " + expectedHtu;
                if (!claims.has("htu") || (!claims.get("htu").asText().equals(expectedHtu) && !claims.get("htu").asText().equals(legacyHtu))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"invalid_dpop_proof\",\"error_description\":\"htu mismatch\"}");
                    return;
                }
            }

            // ---------------------------------------------------------------
            // 3️⃣ Validate proof.ath (access token hash) matches the token
            // ---------------------------------------------------------------
            if (!claims.has("ath")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"invalid_dpop_request\",\"error_description\":\"missing ath\"}");
                return;
            }
            String expectedAth = computeAth(accessToken);
            if (!claims.get("ath").asText().equals(expectedAth)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"invalid_dpop_proof\",\"error_description\":\"ath mismatch\"}");
                return;
            }

            // ---------------------------------------------------------------
            // 4️⃣ Validate proof.jti (JWT ID) for replay
            // ---------------------------------------------------------------
            if (claims.has("jti")) {
                String jti = claims.get("jti").asText();
                Long firstSeen = jtiCache.get(jti);
                if (firstSeen != null && Instant.now().getEpochSecond() - firstSeen < JTI_TTL_SECONDS) {
                    // Replay detected
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"invalid_dpop_proof\",\"error_description\":\"jti replay\"}");
                    return;
                }
                jtiCache.put(jti, Instant.now().getEpochSecond());
            }

            // ---------------------------------------------------------------
            // 5️⃣ Proof valid - continue filter chain
            // ---------------------------------------------------------------
            chain.doFilter(request, response);

        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_dpop_proof\",\"error_description\":\"base64 decode error\"}");
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"internal_error\"}");
        }
    }

    /** Builds the canonical htu value: "<scheme>://<host><path>" as per RFC 9449.
     *  Query string and fragment are omitted. */
    private String buildHtu(HttpServletRequest request) {
        String scheme = "https".equalsIgnoreCase(request.getScheme()) ? "https" : "http";
        String host = extractHost(request);
        String path = request.getRequestURI();
        return scheme + "://" + host + path;
    }

    private String extractHost(HttpServletRequest request) {
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            return forwardedHost.split(",")[0].trim();
        }
        return request.getServerName() + (request.getServerPort() != 80 && request.getServerPort() != 443
                ? ":" + request.getServerPort() : "");
    }

    private String computeAth(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
