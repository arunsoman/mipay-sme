package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class IntrospectionController {

    private final ObjectMapper mapper;

    public IntrospectionController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** RFC 7662 – Token Introspection endpoint.
     *  POST /api/auth/introspect
     *  Body: { "token": "...", "token_type_hint": "access_token" }  (optional)
     *  Response: { "active": true|false, "sub": "...", "iss": "...", "exp": ..., "iat": ..., "jti": ... }
     *  FAPI 2.0 resource servers use this to check token validity and revocation.
     */
    @PostMapping("/introspect")
    public void introspect(@RequestBody(required = false) Map<String, String> body,
                           HttpServletRequest request, HttpServletResponse response) throws IOException {

        String token = null;
        if (body != null && body.containsKey("token")) {
            token = body.get("token");
        } else {
            // Fallback: pull the Bearer token from the Authorization header
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring(7);
            }
        }

        if (token == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            mapper.writeValue(response.getOutputStream(), Map.of("active", false, "error", "missing token"));
            return;
        }

        // Basic validation: check expiry, issuer, and a minimal set of claims.
        // In a full implementation you would query the authorization server / database.
        // Here we just decode the JWT header/payload (the token is a JWS if signed,
        // but for demo we treat it as unsigned JSON; adjust as needed).
        JsonNode payload;
        try {
            // The token may be a compact JWS (three dot-separated parts). Try to parse.
            String[] parts = token.split("\\.", -1);
            if (parts.length == 3) {
                // decode payload (second part)
                byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
                payload = mapper.readTree(decoded);
            } else {
                // treat as opaque; assume active for demo
                payload = null;
            }
        } catch (Exception e) {
            payload = null;
        }

        boolean active = payload != null
                && payload.has("exp")
                && Instant.now().getEpochSecond() < payload.get("exp").asLong()
                && payload.has("iss")
                && payload.has("sub");

        mapper.writeValue(response.getOutputStream(), Map.of(
                "active", active,
                "sub", payload != null ? payload.get("sub") : null,
                "iss", payload != null ? payload.get("iss") : null,
                "exp", payload != null ? payload.get("exp") : null,
                "iat", payload != null ? payload.get("iat") : null,
                "jti", payload != null ? payload.get("jti") : null
        ));
    }
}