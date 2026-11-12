package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.security.rbac.MeshTls;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * The core's client for the comms plane (the only contact writer per comms-design D10).
 * Contacts are user-owned refs ("user:<uid>"); messages carry event codes from the
 * platform's allowed prefixes (account.*, auth.security.*). A blank commsUrl means the
 * plane is not wired: every call fails closed so the outbox keeps retrying delivery.
 */
@Component
@RequiredArgsConstructor
public class CommsClient {
    static final String EVENT_SETUP_LINK = "account.setup";
    static final String EVENT_RESET_LINK = "auth.security.reset_link";

    private final AppProperties properties;
    private final ObjectMapper mapper;
    private HttpClient http;

    public void putContacts(String uid, String sms, String email) {
        try {
            var contacts = new java.util.ArrayList<Map<String, Object>>();
            if (sms != null && !sms.isBlank()) contacts.add(Map.of("channel", "sms", "address", sms,
                    "verified", false, "primary", true));
            if (email != null && !email.isBlank()) contacts.add(Map.of("channel", "email", "address", email,
                    "verified", false, "primary", true));
            if (contacts.isEmpty()) return;
            call("PUT", "/subjects/user:" + uid + "/contacts", Map.of("contacts", contacts), null);  // contact PUT is a replace-all: natively idempotent
        } catch (Exception e) {
            throw new IllegalStateException("comms contact sync failed: " + e.getMessage(), e);
        }
    }

    /** Sends one rendered message; idempotent per (subject, link) so outbox retries never double-send. */
    public void sendLink(String eventCode, String uid, String subjectToken, String link, String name, Instant expiresAt) {
        byte[] mac;
        try {
            mac = MessageDigest.getInstance("SHA-256")
                    .digest((subjectToken + ":" + link).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String idem = "link:" + uid + ":" + HexFormat.of().formatHex(mac, 0, 6);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("eventCode", eventCode);
        body.put("to", java.util.List.of("user:" + uid));
        body.put("data", Map.of("link", link, "name", name == null || name.isBlank() ? "MiPay user" : name));
        if (expiresAt != null) body.put("expiresAt", expiresAt.toString());
        try {
            call("POST", "/messages", body, idem);
        } catch (Exception e) {
            throw new IllegalStateException("comms send failed: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> contacts(String uid) {
        return read("/subjects/user:" + uid + "/contacts");
    }

    public Map<String, Object> sandboxOutbox(String recipient, String event) {
        String q = (recipient == null || recipient.isBlank() ? "" : "?recipient=" + recipient)
                + (event == null || event.isBlank() ? "" : (recipient.isBlank() ? "?" : "&") + "event=" + event);
        return read("/sandbox/outbox" + q);
    }

    private Map<String, Object> read(String path) {
        try {
            return mapper.convertValue(call("GET", path, null, null), Map.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("comms read failed: " + e.getMessage(), e);
        }
    }

    private JsonNode call(String method, String path, Object body, String idem) throws Exception {
        if (properties.getCommsUrl() == null || properties.getCommsUrl().isBlank())
            throw new IllegalStateException("comms plane is not configured (app.comms-url is blank)");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getCommsUrl() + path))
                .timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        if (idem != null) builder.header("Idempotency-Key", idem);
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        } else {
            builder.GET();
        }
        HttpResponse<String> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("comms " + method + " " + path + " returned " + response.statusCode());
        return response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    }

    private HttpClient client() throws Exception {
        HttpClient local = http;
        if (local == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().sslContext(MeshTls.clientContext())
                            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
                }
                local = http;
            }
        }
        return local;
    }
}
