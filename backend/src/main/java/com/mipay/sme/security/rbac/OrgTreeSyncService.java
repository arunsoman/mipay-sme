package com.mipay.sme.security.rbac;

import com.mipay.sme.domain.org.OrgClosure;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrganizationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
 * Pushes the MiPay org closure table to trustplane RBAC so that permission
 * inheritance can be evaluated across the org hierarchy
 * (docs/plans/fapi2-design.md §9.2).
 *
 * Every tenant in a manufacturer org has an RBAC permission set that is a
 * subset of what its parent org has — permissions flow DOWN the org tree.
 * RBAC evaluates this by joining org_closure: an assignment in tenant A
 * applies to tenant B if B is a descendant of A in the closure table.
 *
 * Called at boot (ApplicationReadyEvent) and whenever the org tree changes.
 */
@Service
@Slf4j
public class OrgTreeSyncService {

    private final OrgClosureRepository closureRepo;
    private final OrganizationRepository orgRepo;

    private String rbacUrl;

    public OrgTreeSyncService(OrgClosureRepository closureRepo,
                              OrganizationRepository orgRepo) {
        this.closureRepo = closureRepo;
        this.orgRepo = orgRepo;
    }

    @Transactional(readOnly = true)
    @EventListener(ApplicationReadyEvent.class)
    public void syncAtBoot() {
        String url = System.getenv("APP_RBAC_REGISTRY_URL");
        if (url == null || url.isBlank()) {
            log.info("[rbac-sync] APP_RBAC_REGISTRY_URL not set - skipping org tree sync");
            return;
        }
        // registry URL is .../permissions/register; sync endpoint is .../orgs/sync
        rbacUrl = url.replace("/permissions/register", "/orgs/sync");
        sync();
    }

    /** Push the current org closure + tenant mapping to RBAC. Call after any org-tree change. */
    @Transactional(readOnly = true)
    public void sync() {
        if (rbacUrl == null || rbacUrl.isBlank()) return;

        try {
            List<Map<String, Object>> closure = closureRepo.findAll().stream()
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ancestor", c.getAncestorId());
                        m.put("descendant", c.getDescendantId());
                        m.put("depth", c.getDepth());
                        return m;
                    })
                    .collect(Collectors.toList());

            List<Map<String, Object>> mapping = orgRepo.findAll().stream()
                    .filter(o -> o.getId() != null)
                    .map(o -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("org", o.getId());
                        // tenant id == org id (same namespace in this deployment)
                        m.put("tenant", o.getId());
                        m.put("name", o.getName());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("closure", closure);
            body.put("tenantMapping", mapping);

            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);

            HttpClient client = HttpClient.newBuilder()
                    .sslContext(MeshTls.clientContext())
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder(URI.create(rbacUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Mesh-Service", "mipay")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                log.info("[rbac-sync] org tree synced: {} closure rows, {} tenant mappings",
                        closure.size(), mapping.size());
            } else {
                log.warn("[rbac-sync] sync refused: {} {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[rbac-sync] sync failed: {}", e.getMessage());
        }
    }
}
