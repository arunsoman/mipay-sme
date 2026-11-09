package com.mipay.sme.security;

import com.mipay.sme.domain.core.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * HS256 JWT carrying identity + tenant binding claims.
 *
 * Every token carries a {@code jti} (JWT ID). The jti is persisted SHA-256
 * hashed in devices_sessions, so JwtAuthFilter can reject a live token the
 * moment its session is revoked (TC-SEC-04, credential-compromise response).
 */
@Service
public class JwtService {

    public static final Duration TTL = Duration.ofHours(12);

    @Value("${app.jwt-secret}")
    private String secret;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(java.util.Arrays.copyOf(secret.getBytes(StandardCharsets.UTF_8), 32));
    }

    /** Issued token plus its jti and expiry, so the caller can register the session. */
    public record IssuedToken(String token, String jti, Instant expiresAt) { }

    public IssuedToken issue(User u, String merchantId, String supplierId, String lenderId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", u.getRole().name());
        claims.put("phone", u.getPhone());
        claims.put("name", u.getName() == null ? "" : u.getName());
        if (merchantId != null) claims.put("merchantId", merchantId);
        if (supplierId != null) claims.put("supplierId", supplierId);
        if (lenderId != null) claims.put("lenderId", lenderId);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TTL);
        String jti = java.util.UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(jti)
                .subject(u.getId())
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key())
                .compact();
        return new IssuedToken(token, jti, expiresAt);
    }

    /** @throws JwtException on any invalid/expired token. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
