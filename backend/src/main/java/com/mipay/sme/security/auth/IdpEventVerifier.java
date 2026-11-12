package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.security.rbac.MeshTls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies IdP auth-event JWS (typ auth-event+jwt, ES256) against the IdP's live KWKS
 * ("verifiable against the IdP JWKS" per the IdP contract): signature, kid, alg, typ,
 * issuer, audience "mipay-core", iat/exp freshness and jti. Together with the transactional
 * event-id dedup this is the authentication of the core's ingest endpoint — only the IdP
 * holds the ES256 signing key, so a verified event authenticates the mesh caller.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdpEventVerifier {
    static final long LEEWAY_SECONDS = 60;
    private static final Duration JWKS_TTL = Duration.ofMinutes(5);
    private static final String TYPE = "auth-event+jwt";
    private static final String AUDIENCE = "mipay-core";
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, ECPublicKey> cache = new ConcurrentHashMap<>();
    private volatile Instant cacheAt = Instant.EPOCH;
    private volatile HttpClient http;

    public record AuthEvent(String id, String provider, String type, String subject, String uid,
                            Instant at, Map<String, Object> detail) { }

    public static final class RejectedException extends RuntimeException {
        RejectedException(String reason) { super(reason); }
    }

    public AuthEvent verify(String compactJws) {
        String[] parts = compactJws.split("\\.");
        if (parts.length != 3) throw new RejectedException("not a compact JWS");
        JsonNode header, claims;
        byte[] signature;
        try {
            Base64.Decoder url = Base64.getUrlDecoder();
            header = mapper.readTree(url.decode(parts[0]));
            claims = mapper.readTree(url.decode(parts[1]));
            signature = url.decode(parts[2]);
        } catch (Exception e) {
            throw new RejectedException("malformed JWS: " + e.getMessage());
        }
        if (!"ES256".equals(header.path("alg").asText()) || !TYPE.equals(header.path("typ").asText()))
            throw new RejectedException("unexpected alg/typ");
        String kid = header.path("kid").asText("");
        if (kid.isBlank()) throw new RejectedException("no kid");
        if (!"trustplane".equals(claims.path("event").path("provider").asText()))
            throw new RejectedException("unknown event provider");
        String jti = claims.path("jti").asText("");
        if (jti.isBlank()) throw new RejectedException("no jti");
        if (!jti.equals(claims.path("event").path("id").asText("")))
            throw new RejectedException("jti/event id mismatch");
        if (!properties.getAuthIssuer().equals(claims.path("iss").asText()))
            throw new RejectedException("unknown issuer");
        if (!AUDIENCE.equals(claims.path("aud").asText(AUDIENCE))
                && !claims.path("aud").toString().contains(AUDIENCE))
            throw new RejectedException("unknown audience");
        long iat = claims.path("iat").asLong(0);
        long exp = claims.path("exp").asLong(0);
        long now = Instant.now().getEpochSecond();
        if (iat > now + LEEWAY_SECONDS) throw new RejectedException("future iat");
        if (exp <= now - LEEWAY_SECONDS) throw new RejectedException("expired event");

        verifySignature(parts[0], parts[1], signature, kid);
        return event(claims);
    }

    private void verifySignature(String headB64, String payloadB64, byte[] signature, String kid) {
        PublicKey key = publicKey(kid);
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update((headB64 + "." + payloadB64).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) throw new RejectedException("bad signature");
        } catch (RejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new RejectedException("signature verification failed: " + e.getMessage());
        }
    }

    /** Resolves the kid from the IdP's JWKS, caching for JWKS_TTL and refreshing on an unknown kid. */
    private ECPublicKey publicKey(String kid) {
        ECPublicKey hit = cache.get(kid);
        if (hit != null && cacheFresh()) return hit;
        synchronized (this) {
            hit = cache.get(kid);
            if (hit != null && cacheFresh()) return hit;
            fetchJwks();
            hit = cache.get(kid);
            if (hit == null) throw new RejectedException("kid not in IdP JWKS: " + kid);
            return hit;
        }
    }

    private boolean cacheFresh() {
        return Duration.between(cacheAt, Instant.now()).compareTo(JWKS_TTL) < 0;
    }

    private void fetchJwks() {
        try {
            HttpClient client = this.http;
            if (client == null) {
                synchronized (this) {
                    if (this.http == null) this.http = HttpClient.newBuilder()
                            .sslContext(MeshTls.clientContext())
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(5)).build();
                    client = this.http;
                }
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getAuthUrl() + "/jwks.json"))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("jwks returned " + response.statusCode());
            JsonNode jwks = mapper.readTree(response.body()).path("keys");
            cacheAt = Instant.now();
            Map<String, ECPublicKey> fresh = new ConcurrentHashMap<>();
            for (JsonNode jwk : jwks) {
                if (!"EC".equals(jwk.path("kty").asText()) || !"P-256".equals(jwk.path("crv").asText())) continue;
                Base64.Decoder url = Base64.getUrlDecoder();
                ECPoint point = new ECPoint(new BigInteger(1, url.decode(jwk.path("x").asText())),
                        new BigInteger(1, url.decode(jwk.path("y").asText())));
                AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                params.init(new ECGenParameterSpec("secp256r1"));
                ECPublicKeySpec spec = new ECPublicKeySpec(point, params.getParameterSpec(ECParameterSpec.class));
                fresh.put(jwk.path("kid").asText(), (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(spec));
            }
            cache.clear();
            cache.putAll(fresh);
            log.debug("refreshed IdP JWKS ({} keys)", fresh.size());
        } catch (RejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new RejectedException("jwks unavailable: " + e.getMessage());
        }
    }

    private AuthEvent event(JsonNode claims) {
        JsonNode ev = claims.path("event");
        Instant at;
        try {
            at = ev.path("at").asText("").isBlank() ? Instant.now() : Instant.parse(ev.path("at").asText());
        } catch (Exception e) {
            at = Instant.now();
        }
        if (ev.path("subject").asText("").isBlank() || ev.path("uid").asText("").isBlank()
                || ev.path("type").asText("").isBlank())
            throw new RejectedException("event payload incomplete");
        return new AuthEvent(ev.path("id").asText(), ev.path("provider").asText(),
                ev.path("type").asText(), ev.path("subject").asText(), ev.path("uid").asText(),
                at, mapper.convertValue(ev.path("detail"), Map.class));
    }
}
