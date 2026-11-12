package com.mipay.sme.security.rbac;

import com.mipay.sme.domain.ops.Suspension;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.SuspensionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Pushes the MiPay org tree to trustplane RBAC (docs/plans/fapi2-design.md §9.2,
 * idp-rbac-cutover-plan.md P2.6a):
 * <ul>
 *   <li>the closure table, so subtree assignments cover descendant orgs;</li>
 *   <li>every org with its type (rows apply per org type), parent, lifecycle and its own active
 *       holds, so RBAC refuses a held org and everything below it before the gateway routes.</li>
 * </ul>
 * When it runs: at boot; right after a suspension or lift commits ({@link OrgTreeChanged});
 * and every 15 s when a cheap fingerprint of orgs, closure and holds has changed (catches
 * every other path: onboarding, lifecycle transitions, KYC outcomes).
 */
@Service
@Slf4j
public class OrgTreeSyncService {

    /** Publish after any change to orgs, the tree or holds; the sync runs after commit. */
    public record OrgTreeChanged(String orgId) { }

    private final OrgClosureRepository closureRepo;
    private final OrganizationRepository orgRepo;
    private final SuspensionRepository suspensionRepo;
    private final JdbcTemplate jdbc;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rbac-org-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private volatile String lastFingerprint = "";
    private volatile String rbacUrl;

    public OrgTreeSyncService(OrgClosureRepository closureRepo, OrganizationRepository orgRepo,
                              SuspensionRepository suspensionRepo, JdbcTemplate jdbc) {
        this.closureRepo = closureRepo;
        this.orgRepo = orgRepo;
        this.suspensionRepo = suspensionRepo;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncAtBoot() {
        String url = System.getenv("APP_RBAC_REGISTRY_URL");
        if (url == null || url.isBlank()) {
            log.info("[rbac-sync] APP_RBAC_REGISTRY_URL not set - skipping org tree sync");
            return;
        }
        // registry URL is .../permissions/register; sync endpoint is .../orgs/sync
        rbacUrl = url.replace("/permissions/register", "/orgs/sync");
        requestSync();
        timer.scheduleWithFixedDelay(this::syncIfChanged, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(OrgTreeChanged e) {
        requestSync();
    }

    /** Coalesces bursts of changes into one sync shortly after. */
    public void requestSync() {
        if (rbacUrl == null) return;
        if (pending.compareAndSet(false, true)) {
            timer.schedule(() -> {
                pending.set(false);
                sync();
            }, 300, TimeUnit.MILLISECONDS);
        }
    }

    String fingerprint() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM organizations) || ':' || (SELECT coalesce(sum(version),0) FROM organizations)
                    || ':' || (SELECT count(*) FROM org_closures)
                    || ':' || (SELECT count(*) FROM suspensions WHERE lifted_at IS NULL)
                    || ':' || (SELECT coalesce(max(greatest(suspended_at, coalesce(lifted_at, suspended_at)))::text, '') FROM suspensions)""",
                String.class);
    }

    void syncIfChanged() {
        try {
            if (!fingerprint().equals(lastFingerprint)) sync();
        } catch (Exception e) {
            log.warn("[rbac-sync] fingerprint check failed: {}", e.getMessage());
        }
    }

    /** Push the current org closure and every org's type, parent, lifecycle and holds. */
    public synchronized void sync() {
        if (rbacUrl == null || rbacUrl.isBlank()) return;
        try {
            String fp = fingerprint();
            List<Map<String, Object>> closure = closureRepo.findAll().stream()
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ancestor", c.getAncestorId());
                        m.put("descendant", c.getDescendantId());
                        m.put("depth", c.getDepth());
                        return m;
                    })
                    .collect(Collectors.toList());

            // each org's OWN active holds; RBAC applies ancestors' holds through the closure
            Map<String, List<String>> holds = new HashMap<>();
            for (Suspension s : suspensionRepo.findByLiftedAtIsNull()) {
                if (s.getOrgId() == null) continue;
                holds.computeIfAbsent(s.getOrgId(), k -> new ArrayList<>())
                        .add(s.getSuspendedBy() == null ? "HOLD" : (s.getSuspendedBy().startsWith("system:") ? s.getSuspendedBy() : "MANUAL"));
            }
            List<Map<String, Object>> orgs = orgRepo.findAll().stream()
                    .filter(o -> o.getId() != null)
                    .map(o -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", o.getId());          // tenant id == org id
                        m.put("name", o.getName());
                        m.put("type", o.getOrgType() == null ? null : o.getOrgType().name());
                        m.put("parent", o.getParentId());
                        m.put("lifecycle", o.getLifecycle() == null ? "ACTIVE" : o.getLifecycle().name());
                        List<String> h = holds.getOrDefault(o.getId(), List.of());
                        m.put("held", !h.isEmpty());
                        m.put("holds", h);
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("closure", closure);
            body.put("orgs", orgs);
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
                lastFingerprint = fp;
                log.info("[rbac-sync] org tree synced: {} orgs ({} held), {} closure rows", orgs.size(), holds.size(), closure.size());
            } else {
                log.warn("[rbac-sync] sync refused: {} {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[rbac-sync] sync failed: {}", e.getMessage());
        }
    }
}
