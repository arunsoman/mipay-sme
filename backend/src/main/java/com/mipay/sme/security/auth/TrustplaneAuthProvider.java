package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.security.rbac.MeshTls;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.mipay.sme.security.auth.AuthPluginContracts.*;

/** The only implementation of the core credential contract. All calls use the core mesh certificate. */
@Component
public final class TrustplaneAuthProvider implements AuthProvider {
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private volatile HttpClient http;

    @Autowired
    public TrustplaneAuthProvider(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    TrustplaneAuthProvider(AppProperties properties, ObjectMapper mapper, HttpClient http) {
        this(properties, mapper);
        this.http = http;
    }

    @Override public String key() { return "trustplane"; }
    @Override public String issuer() { return properties.getAuthIssuer(); }

    @Override
    public Enrolment enrol(EnrolRequest request) {
        require(request.userId());
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "uid", request.userId(), "login_name", require(request.loginName()),
                "phone", request.phone() == null ? "" : request.phone(),
                "display_name", request.displayName() == null ? "" : request.displayName()));
        // credential import (P3.10 backfill): a bcrypt hash copies the account over ACTIVE,
        // no set-up link is issued and the response has no link/expires_at
        if (request.passwordHash() != null && !request.passwordHash().isBlank()) {
            body.put("password_hash", request.passwordHash());
        }
        JsonNode result = call("POST", "/accounts", body, "enrol:" + request.userId());
        return enrolment(result);
    }

    @Override
    public Enrolment resetCredential(String subject, ResetScope scope) {
        JsonNode result = call("POST", account(subject) + "/reset-link", Map.of("scope", scope.name()));
        return new Enrolment(subject, result.path("link").asText(null),
                result.hasNonNull("expires_at") ? Instant.parse(result.get("expires_at").asText()) : null);
    }

    @Override public void disable(String subject, String reason) {
        call("POST", account(subject) + "/disable", Map.of("reason", reason == null ? "" : reason));
    }
    @Override public void enable(String subject) { call("POST", account(subject) + "/enable", Map.of()); }
    @Override public void revokeSessions(String subject) { call("POST", account(subject) + "/revoke-sessions", Map.of()); }
    @Override public void updateLoginName(String subject, String loginName, String phone) {
        call("PATCH", account(subject), Map.of("login_name", require(loginName), "phone", phone == null ? "" : phone));
    }
    @Override public void setMfaProfile(String subject, MfaProfile profile, boolean orgRequiresMfa) {
        call("PUT", account(subject) + "/mfa-profile", Map.of(
                "profile", profile.name(), "org_requires_mfa", orgRequiresMfa));
    }
    @Override public void unlink(String subject) { call("DELETE", account(subject), null); }

    @Override
    public Assurance assuranceOf(String acr, List<String> amr) {
        if ("aal2".equals(acr) && amr != null &&
                amr.stream().anyMatch(m -> m.equals("totp") || m.equals("sms-mfa"))) return Assurance.AAL2;
        return Assurance.AAL1;
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("required auth-provider value is blank");
        return value;
    }

    private static String account(String subject) {
        return "/accounts/" + URLEncoder.encode(require(subject), StandardCharsets.UTF_8);
    }

    private Enrolment enrolment(JsonNode result) {
        String subject = require(result.path("subject").asText(null));
        // an imported-credential enrolment comes back ACTIVE with no link to deliver
        if ("ACTIVE".equals(result.path("state").asText(""))) {
            return new Enrolment(subject, null, null);
        }
        return new Enrolment(subject, require(result.path("link").asText(null)),
                Instant.parse(require(result.path("expires_at").asText(null))));
    }

    private HttpClient client() {
        if (http == null) {
            synchronized (this) {
                if (http == null) {
                    try {
                        http = HttpClient.newBuilder().sslContext(MeshTls.clientContext())
                                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
                    } catch (Exception e) {
                        throw new IllegalStateException("core mesh TLS is unavailable", e);
                    }
                }
            }
        }
        return http;
    }

    private JsonNode call(String method, String path, Object body) {
        return call(method, path, body, null);
    }

    private JsonNode call(String method, String path, Object body, String idempotencyKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getAuthUrl() + path))
                    .timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            String payload = body == null ? "" : mapper.writeValueAsString(body);
            if (body != null) builder.header("Content-Type", "application/json");
            HttpRequest request = builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new AuthProviderException(response.statusCode(), "IdP " + method + " " + path + " returned " + response.statusCode());
            return mapper.readTree(response.body());
        } catch (AuthProviderException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AuthProviderException(503, "IdP request failed: " + e, e);
        }
    }

    public static final class AuthProviderException extends RuntimeException {
        private final int status;
        public AuthProviderException(int status, String message) { super(message); this.status = status; }
        public AuthProviderException(int status, String message, Throwable cause) { super(message, cause); this.status = status; }
        public int status() { return status; }
    }
}
