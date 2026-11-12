package com.mipay.sme.security.auth;

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

/** Reads the live RBAC assignment for the exceptional PENDING-org KYC applicant. */
@Component
public class RbacApplicantClient {
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private volatile HttpClient http;

    @Autowired
    public RbacApplicantClient(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    RbacApplicantClient(AppProperties properties, ObjectMapper mapper, HttpClient http) {
        this(properties, mapper);
        this.http = http;
    }

    public boolean hasActiveApplicantRole(String uid, String orgId) {
        try {
            if (http == null) {
                synchronized (this) {
                    if (http == null) http = HttpClient.newBuilder().sslContext(MeshTls.clientContext())
                            .connectTimeout(Duration.ofSeconds(2)).build();
                }
            }
            String path = "/rbac/internal/assignments?subject=" + URLEncoder.encode(uid, StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create(properties.getRbacUrl() + path))
                    .header("X-Mesh-Service", "mipay").timeout(Duration.ofSeconds(3)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            var rows = mapper.readTree(response.body());
            if (!rows.isArray()) return false;
            for (var row : rows) {
                if (!"kyc_applicant".equals(row.path("role").asText()) ||
                        !"ACTIVE".equals(row.path("status").asText()) ||
                        !orgId.equals(row.path("tenant").asText())) continue;
                String expiry = row.path("expires_at").asText("");
                if (expiry.isBlank() || Instant.parse(expiry).isAfter(Instant.now())) return true;
            }
            return false;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
