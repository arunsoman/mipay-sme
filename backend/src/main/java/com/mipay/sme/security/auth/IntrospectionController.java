package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class IntrospectionController {

    private final ObjectMapper mapper;
    private final JwtService jwtService;

    @Value("${app.jwt-issuer:}")
    private String expectedIssuer;

    public IntrospectionController(ObjectMapper mapper, JwtService jwtService) {
        this.mapper = mapper;
        this.jwtService = jwtService;
    }

    /** RFC 7662 – Token Introspection endpoint.
     *  POST /api/auth/introspect
     *  Body: { "token": "...", "token_type_hint": "access_token" }  (optional)
     *  Response: { "active": true|false, "sub": "...", "iss": "...", "exp": ..., "iat": ..., "jti": ... }
     *  FAPI 2.0 resource servers use this to check token validity and revocation.
     *
     *  Security: the JWT signature is verified by JwtService (HS256 + shared secret).
     *  Tokens with an invalid/forged signature, wrong issuer, or past expiry are
     *  rejected with active:false without leaking any claim values.
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
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), Map.of("active", false, "error", "missing token"));
            return;
        }

        // Verify the JWT signature and parse claims using JwtService (HS256 + secret).
        // Any invalid/expired/tampered token throws JwtException → active: false.
        // This prevents forged JWTs from being reported as active (Bug #3).
        Claims claims;
        try {
            claims = jwtService.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), Map.of("active", false));
            return;
        }

        // Verify the issuer claim when an expected issuer is configured (Bug #3).
        String iss = claims.getIssuer();
        if (expectedIssuer != null && !expectedIssuer.isBlank()) {
            if (!expectedIssuer.equals(iss)) {
                response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(), Map.of("active", false));
                return;
            }
        }

        // At this point the signature is valid and the token is not expired
        // (jjwt enforces exp during parse). Build the RFC 7662 response body
        // using LinkedHashMap so null values for optional claims are allowed
        // without Map.of() throwing NullPointerException (Bug #4).
        boolean active = claims.getSubject() != null
                && claims.getExpiration() != null
                && Instant.now().isBefore(claims.getExpiration().toInstant());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("active", active);
        if (active) {
            resp.put("sub", claims.getSubject());
            resp.put("iss", iss);
            if (claims.getExpiration() != null) {
                resp.put("exp", claims.getExpiration().toInstant().getEpochSecond());
            }
            // iat and jti are optional; include only when present (Bug #4: no nulls in Map.of)
            if (claims.getIssuedAt() != null) {
                resp.put("iat", claims.getIssuedAt().toInstant().getEpochSecond());
            }
            if (claims.getId() != null) {
                resp.put("jti", claims.getId());
            }
        }
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), resp);
    }
}
