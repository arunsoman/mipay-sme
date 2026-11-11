package com.mipay.sme.security.rbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client for the trustplane RBAC service. Uses mTLS (MeshTls) to call
 * https://trustplane-rbac:8443/rbac/admin/... and exposes a simplified
 * surface for the admin portal permission matrix UI.
 */
@Service
@Slf4j
public class RbacClientService {

    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RbacClientService(AppProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private String rbacUrl() {
        return props.getRbacUrl();
    }

    /** List all roles with their permissions. */
    @Transactional(readOnly = true)
    public List<Dtos.RbacRoleView> listRoles() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rbacUrl() + "/rbac/admin/roles"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("RBAC listRoles failed: " + response.statusCode() + " " + response.body());
        }
        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), List.class);
        return raw.stream()
                .map(m -> new Dtos.RbacRoleView(
                        (String) m.get("name"),
                        (String) m.getOrDefault("description", ""),
                        (List<String>) m.getOrDefault("permissions", List.of())
                ))
                .collect(Collectors.toList());
    }

    /** Get a single role with its permissions. */
    @Transactional(readOnly = true)
    public Dtos.RbacRoleView getRole(String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rbacUrl() + "/rbac/admin/roles/" + name))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("RBAC getRole failed: " + response.statusCode() + " " + response.body());
        }
        Map<String, Object> m = objectMapper.readValue(response.body(), Map.class);
        return new Dtos.RbacRoleView(
                (String) m.get("name"),
                (String) m.getOrDefault("description", ""),
                (List<String>) m.getOrDefault("permissions", List.of())
        );
    }

    /** Update a role's permissions. */
    @Transactional
    public Dtos.RbacRoleView updateRole(String name, List<String> permissions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("permissions", permissions);
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rbacUrl() + "/rbac/admin/roles/" + name))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("RBAC updateRole failed: " + response.statusCode() + " " + response.body());
        }
        Map<String, Object> m = objectMapper.readValue(response.body(), Map.class);
        return new Dtos.RbacRoleView(
                (String) m.get("name"),
                (String) m.getOrDefault("description", ""),
                (List<String>) m.getOrDefault("permissions", List.of())
        );
    }

    /** List all users. */
    @Transactional(readOnly = true)
    public List<Dtos.RbacUserView> listUsers() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rbacUrl() + "/rbac/admin/users"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("RBAC listUsers failed: " + response.statusCode() + " " + response.body());
        }
        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), List.class);
        return raw.stream()
                .map(m -> new Dtos.RbacUserView(
                        (String) m.get("id"),
                        (String) m.getOrDefault("display_name", ""),
                        (String) m.getOrDefault("supplier_id", ""),
                        (String) m.getOrDefault("kind", ""),
                        (String) m.getOrDefault("status", "")
                ))
                .collect(Collectors.toList());
    }

    /** List all role assignments. */
    @Transactional(readOnly = true)
    public List<Dtos.RbacAssignmentView> listAssignments() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rbacUrl() + "/rbac/admin/assignments"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("RBAC listAssignments failed: " + response.statusCode() + " " + response.body());
        }
        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), List.class);
        return raw.stream()
                .map(m -> new Dtos.RbacAssignmentView(
                        (String) m.get("id"),
                        (String) m.get("subject"),
                        (String) m.get("role"),
                        (String) m.getOrDefault("tenant", ""),
                        (String) m.getOrDefault("scope", ""),
                        (String) m.getOrDefault("status", "")
                ))
                .collect(Collectors.toList());
    }

    /** Create a role assignment. */
    @Transactional
    public Dtos.RbacAssignmentView createAssignment(String subject, String role, String tenant, String scope)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("role", role);
        body.put("tenant", tenant == null ? "" : tenant);
        body.put("scope", scope == null ? "" : scope);
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rbacUrl() + "/rbac/admin/assignments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("RBAC createAssignment failed: " + response.statusCode() + " "
                    + response.body());
        }
        Map<String, Object> m = objectMapper.readValue(response.body(), Map.class);
        return new Dtos.RbacAssignmentView(
                (String) m.get("id"),
                (String) m.get("subject"),
                (String) m.get("role"),
                (String) m.getOrDefault("tenant", ""),
                (String) m.getOrDefault("scope", ""),
                (String) m.getOrDefault("status", "")
        );
    }

    /** Delete a role assignment. */
    @Transactional
    public void deleteAssignment(String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rbacUrl() + "/rbac/admin/assignments/" + id))
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("RBAC deleteAssignment failed: " + response.statusCode() + " "
                    + response.body());
        }
    }
}
