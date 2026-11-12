package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RbacApplicantClientTest {
    @Test void acceptsOnlyCurrentApplicantAssignmentForThatOrg() throws Exception {
        AtomicReference<String> reply = new AtomicReference<>("[]");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rbac/internal/assignments", exchange -> {
            assertEquals("mipay", exchange.getRequestHeaders().getFirst("X-Mesh-Service"));
            assertEquals("subject=u1", exchange.getRequestURI().getRawQuery());
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            AppProperties props = new AppProperties();
            props.setRbacUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new RbacApplicantClient(props, new ObjectMapper(), HttpClient.newHttpClient());
            assertFalse(client.hasActiveApplicantRole("u1", "org1"));
            reply.set("[{\"role\":\"kyc_applicant\",\"tenant\":\"org1\",\"status\":\"ACTIVE\",\"expires_at\":\"" +
                    Instant.now().plusSeconds(300) + "\"}]");
            assertTrue(client.hasActiveApplicantRole("u1", "org1"));
            assertFalse(client.hasActiveApplicantRole("u1", "org2"));
            reply.set("[{\"role\":\"kyc_applicant\",\"tenant\":\"org1\",\"status\":\"REVOKED\"}]");
            assertFalse(client.hasActiveApplicantRole("u1", "org1"));
        } finally {
            server.stop(0);
        }
    }
}
