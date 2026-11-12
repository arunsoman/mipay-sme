package com.mipay.sme.security.auth;

import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.core.AuthOutbox;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.repository.AuthOutboxRepository;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The processor against the real components, no mocks: TrustplaneAuthProvider speaks real mTLS
 * HTTPS to the real Go IdP (socat-forwarded from 127.0.0.1:18443 to the sme-mesh), Spring Data
 * writes identity_links/auth_outbox to a real throwaway Postgres, and the IdP proves its
 * Idempotency-Key replay so a retried enrol returns the same link — retry can never invalidate
 * a delivered link. Needs: xrbac-test-pg (or any Postgres at 127.0.0.1:25433) and the sme-mesh
 * IdP up; PKI copied into build/test-pki (Makefile target auth-it).
 */
@ExtendWith(AuthOutboxProcessorRealIdpIT.RealInfra.class)
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:25433/auth_it_processor",
        "spring.datasource.username=t",
        "spring.datasource.password=t",
        "app.identity-mode=gateway",
        "app.auth-provider=trustplane",
        "app.auth-url=https://127.0.0.1:18443",
        "app.outbox.poll-millis=3600000",
        "app.gateway-context-secret-file=build/test-pki/_vol/ctx-secret"
})
class AuthOutboxProcessorRealIdpIT {

    static class RealInfra implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext ctx) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", 25433), 500);
                return ConditionEvaluationResult.enabled("test Postgres reachable");
            } catch (Exception e) {
                return ConditionEvaluationResult.disabled("no Postgres at 127.0.0.1:25433 (auth-it infra)");
            }
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        LinkDelivery recordingSink() { return new Recorder(); }
    }

    static final class Recorder implements LinkDelivery {
        final List<Request> received = new ArrayList<>();
        volatile boolean failNext = false;
        @Override public void deliver(Request request) {
            received.add(request);
            if (failNext) throw new IllegalStateException("comms sandbox forced failure");
        }
    }

    @Autowired Recorder recorder;
    @Autowired AuthOutboxService outbox;
    @Autowired AuthOutboxProcessor processor;
    @Autowired AuthProviderRegistry registry;
    @Autowired UserRepository users;
    @Autowired IdentityLinkRepository links;
    @Autowired AuthOutboxRepository outboxRepo;
    @Autowired JdbcTemplate jdbc;

    private static final String ISSUER = "https://idp.fapi.local:8443";

    private void provisionUser(String uid, String phone) {
        String rootId = jdbc.queryForObject("SELECT id FROM organizations WHERE org_type='PLATFORM' LIMIT 1", String.class);
        User user = new User();
        user.setId(uid);
        user.setName("Real IT " + uid);
        user.setPhone(phone);
        user.setRole(Enums.UserRole.TPTS_OPS);
        user.setStatus(Enums.UserStatus.PENDING);
        user.setOrgId(rootId);
        users.save(user);
        // no identity_links row here: by design (V15: subject NOT NULL) the row is created
        // by the processor when the real IdP enrolment first returns the subject.
    }

    @BeforeAll
    static void provisionDb() throws Exception {
        try {
            javax.net.ssl.SSLContext probe = com.mipay.sme.security.rbac.MeshTls.clientContext();
            assertNotNull(probe);
        } catch (Exception e) {
            throw new IllegalStateException("MeshTls probe failed (MESH_CERT=" + System.getenv("MESH_CERT")
                    + " MESH_KEY=" + System.getenv("MESH_KEY") + " MESH_CA=" + System.getenv("MESH_CA") + ")", e);
        }
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_processor WITH (FORCE)");
            s.execute("CREATE DATABASE auth_it_processor");
        }
    }

    @Test
    void enrolReachesRealIdpAndPersistsItsSubject() {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        provisionUser(uid, "+264810" + System.nanoTime() % 100000000);
        outbox.enqueue(uid, "trustplane", "ENROL",
                Map.of("user_id", uid, "login_name", "it-" + uid, "phone", "+264810" + System.nanoTime() % 100000000,
                        "display_name", "Real IT"), "enrol:" + uid);
        int receivedBefore = recorder.received.size();
        processor.tick();
        AuthOutbox done = outboxRepo.findAll().stream().filter(r -> uid.equals(r.getUserId())).findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.DONE, done.getState(), "outbox row completed: " + done.getLastError());
        IdentityLink row = links.findByUserId(uid).stream().filter(l -> "trustplane".equals(l.getProvider())).findFirst().orElseThrow();
        assertTrue(row.getSubject() != null && !row.getSubject().isBlank(), "real IdP subject persisted");
        assertEquals(receivedBefore + 1, recorder.received.size());
        LinkDelivery.Request req = recorder.received.get(receivedBefore);
        assertEquals(uid, req.userId());
        assertEquals(row.getSubject(), req.subject());
        assertTrue(req.oneTimeLink().startsWith("https"), "one-time setup link handed to comms");
        assertTrue(req.expiresAt().isAfter(Instant.now().plus(Duration.ofHours(70)))
                        && req.expiresAt().isBefore(Instant.now().plus(Duration.ofHours(74))),
                "setup link lives ~72h: " + req.expiresAt());
    }

    @Test
    void retriedEnrolReplaysTheSameLinkNeverInvalidating() {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        provisionUser(uid, "+264810" + System.nanoTime() % 100000000);
        // 1. enrol + force the comms sink to fail on the first delivery attempt
        recorder.failNext = true;
        int baseline = recorder.received.size();
        outbox.enqueue(uid, "trustplane", "ENROL",
                Map.of("user_id", uid, "login_name", "it-" + uid, "display_name", "Retry IT"), "enrol:" + uid);
        processor.tick();
        AuthOutbox pending = outboxRepo.findAll().stream().filter(r -> uid.equals(r.getUserId())).findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.PENDING, pending.getState(), "delivery failure retries");
        assertEquals(baseline + 1, recorder.received.size(), "attempt 1 was recorded before the forced failure");
        String seenLink = recorder.received.get(baseline).oneTimeLink();
        // 2. repair the sink and let the configured backoff elapse; the retry re-runs ENROL
        //    with the same Idempotency-Key
        recorder.failNext = false;
        jdbc.update("UPDATE auth_outbox SET available_at = now() WHERE user_id = ?", uid);
        processor.tick();
        AuthOutbox done = outboxRepo.findAll().stream().filter(r -> uid.equals(r.getUserId())).findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.DONE, done.getState(), "retry completes: " + done.getLastError());
        assertEquals(baseline + 2, recorder.received.size());
        // 3. The REAL IdP replayed the SAME token for the same (subject, idempotency-key):
        //    the delivered link was never silently invalidated.
        assertEquals(seenLink, recorder.received.get(baseline + 1).oneTimeLink());
    }

    @Test
    void resetLinkIssuesASecondIndependentLink() {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        provisionUser(uid, "+264810" + System.nanoTime() % 100000000);
        outbox.enqueue(uid, "trustplane", "ENROL", Map.of("user_id", uid, "login_name", "it-" + uid), "enrol:" + uid);
        processor.tick();
        IdentityLink row = links.findByUserId(uid).stream().filter(l -> "trustplane".equals(l.getProvider())).findFirst().orElseThrow();
        outbox.enqueue(uid, "trustplane", "RESET_LINK", Map.of("user_id", uid, "subject", row.getSubject()), "reset:" + uid);
        int before = recorder.received.size();
        processor.tick();
        assertEquals(before + 1, recorder.received.size());
        LinkDelivery.Request req = recorder.received.get(before);
        assertEquals("RESET_LINK", req.kind());
        assertNotEquals(recorder.received.get(0).oneTimeLink(), req.oneTimeLink(),
                "reset link is a fresh one-time token");
        assertEquals(row.getSubject(), req.subject());
    }

    @Test
    void tickIsANoOpWhenProviderDisabled() {
        AppProperties props = new AppProperties();
        props.setIdentityMode("legacy");
        AuthOutboxProcessor legacy =
                new AuthOutboxProcessor(outbox, registry, props, links, new com.fasterxml.jackson.databind.ObjectMapper(), List.of(recorder));
        legacy.tick(); // must not lease (identityMode != gateway)
        // prove nothing new was leased: no RUNNING rows appeared
        assertTrue(outboxRepo.findAll().stream().noneMatch(r -> AuthOutbox.State.RUNNING == r.getState()));
    }

    @AfterAll
    static void dropDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_processor WITH (FORCE)");
        } catch (Exception ignored) { }
    }
}
