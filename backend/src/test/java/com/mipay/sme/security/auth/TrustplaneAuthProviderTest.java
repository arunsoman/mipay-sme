package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mipay.sme.security.auth.AuthPluginContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class TrustplaneAuthProviderTest {
    @Test void mapsCoreCommandsToIdpAccountApi() throws Exception {
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body
                    + " | " + exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            String answer = switch (exchange.getRequestURI().getPath()) {
                case "/accounts" -> "{\"subject\":\"sub_abc\",\"link\":\"https://idp.test/setup\",\"expires_at\":\"2027-01-01T00:00:00Z\"}";
                case "/accounts/sub_abc/reset-link" -> "{\"subject\":\"sub_abc\",\"link\":\"https://idp.test/recovery\",\"expires_at\":\"2027-01-02T00:00:00Z\"}";
                default -> "{}";
            };
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length());
            try (var out = exchange.getResponseBody()) { out.write(answer.getBytes(StandardCharsets.UTF_8)); }
        });
        server.start();
        try {
            AppProperties props = new AppProperties();
            props.setAuthUrl("http://127.0.0.1:" + server.getAddress().getPort());
            TrustplaneAuthProvider provider = new TrustplaneAuthProvider(props, new ObjectMapper(), HttpClient.newHttpClient());
            var enrolled = provider.enrol(new EnrolRequest("u-1", "alice", "+264810000042", "Alice"));
            assertEquals("sub_abc", enrolled.subject());
            assertEquals("https://idp.test/setup", enrolled.oneTimeLink());
            assertEquals("https://idp.test/recovery", provider.resetCredential("sub_abc", ResetScope.FULL).oneTimeLink());
            provider.setMfaProfile("sub_abc", MfaProfile.ORG_ADMIN, true);
            provider.disable("sub_abc", "suspended");
            provider.enable("sub_abc");
            provider.revokeSessions("sub_abc");
            provider.updateLoginName("sub_abc", "alice2", "+264810000042");
            provider.unlink("sub_abc");
            assertTrue(seen.getFirst().contains("\"uid\":\"u-1\""));
            assertTrue(seen.getFirst().endsWith("| enrol:u-1"));
            assertTrue(seen.stream().anyMatch(s -> s.startsWith("PUT /accounts/sub_abc/mfa-profile ") && s.contains("\"org_requires_mfa\":true")));
            assertTrue(seen.stream().anyMatch(s -> s.startsWith("POST /accounts/sub_abc/disable ")));
            assertTrue(seen.stream().anyMatch(s -> s.startsWith("PATCH /accounts/sub_abc ")));
            assertTrue(seen.stream().anyMatch(s -> s.startsWith("DELETE /accounts/sub_abc ")));
            assertEquals(Assurance.AAL2, provider.assuranceOf("aal2", List.of("pwd", "totp")));
            assertEquals(Assurance.AAL1, provider.assuranceOf("aal2", List.of("pwd")));
        } finally {
            server.stop(0);
        }
    }

    @Test void rejectsInvalidCutoverConfiguration() {
        AppProperties props = new AppProperties();
        props.setIdentityMode("gateway");
        assertThrows(IllegalStateException.class, () -> new AuthProviderRegistry(List.of(), props));
        props.setAuthProvider("trustplane");
        props.setAuthUrl("http://idp:8443");
        TrustplaneAuthProvider provider = new TrustplaneAuthProvider(props, new ObjectMapper(), HttpClient.newHttpClient());
        assertThrows(IllegalStateException.class, () -> new AuthProviderRegistry(List.of(provider), props));
    }
}
