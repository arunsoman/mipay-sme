package com.mipay.sme.security.auth;

import com.mipay.sme.domain.core.AuthOutbox;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.repository.AuthOutboxRepository;
import com.mipay.sme.repository.DeviceSessionRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every user-status writer against the real IdP and Postgres, no mocks: suspend queues
 * DISABLE + REVOKE_SESSIONS (core sessions die, the IdP account is disabled and the link
 * goes DISABLED), reinstate re-enables and restores the link, archive queues DISABLE + UNLINK
 * and the processor marks the link REVOKED once the real IdP confirms. Binding a pre-binding
 * user enrols it exactly like the self-registration path does. Uses the auth-it infra
 * (Postgres at 127.0.0.1:25433, sme-mesh IdP via 127.0.0.1:18443).
 */
@ExtendWith(WriterSweepRealIT.RealInfra.class)
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:25433/auth_it_writers",
        "spring.datasource.username=t",
        "spring.datasource.password=t",
        "app.identity-mode=gateway",
        "app.auth-provider=trustplane",
        "app.auth-url=https://127.0.0.1:18443",
        "app.outbox.poll-millis=3600000",
        "app.gateway-context-secret-file=build/test-pki/_vol/ctx-secret",
        "logging.level.org.hibernate.SQL=DEBUG",
        "logging.level.org.hibernate.orm.jdbc.bind=TRACE"
})
class WriterSweepRealIT {

    /** The comms plane is not wired in this test: links are recorded instead of delivered. */
    @org.springframework.boot.test.context.TestConfiguration
    static class SinkConfig {
        @org.springframework.context.annotation.Bean
        LinkDelivery recordingSink() {
            return request -> { };
        }
    }

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

    @Autowired UserLifecycleService lifecycle;
    @Autowired AuthOutboxService outbox;
    @Autowired AuthOutboxProcessor processor;
    @Autowired UserRepository users;
    @Autowired IdentityLinkRepository links;
    @Autowired DeviceSessionRepository sessions;
    @Autowired AuthOutboxRepository outboxRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    org.springframework.transaction.support.TransactionTemplate txManager() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @BeforeAll
    static void provisionDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_writers WITH (FORCE)");
            s.execute("CREATE DATABASE auth_it_writers");
        }
    }

    private String rootId() {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE org_type='PLATFORM' LIMIT 1", String.class);
    }

    /** Creates + enrols a user through the real IdP so a link and subject exist. */
    private String enrolled(String uid) {
        User user = lifecycle.create(new UserLifecycleService.Registration(rootId(), "Sweep IT " + uid,
                "sw-" + uid, "+264833" + Math.abs(uid.hashCode()) % 10000000, uid + "@sweep.test",
                Enums.UserRole.TPTS_OPS, Enums.OrgMemberRole.ADMIN, "sweep-it"));
        String realId = user.getId();   // create() owns the id (the outbox FK references it)
        processor.tick();
        AuthOutbox row = outboxRepo.findAll().stream().filter(r -> realId.equals(r.getUserId())
                && "ENROL".equals(r.getCommand())).findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.DONE, row.getState(), "enrol completed: " + row.getLastError());
        return realId;
    }

    private IdentityLink linkOf(String realId) {
        return links.findByUserId(realId).stream().filter(l -> "trustplane".equals(l.getProvider())).findFirst().orElseThrow();
    }

    @Test
    void suspendKillsSessionsDisablesTheIdpAccountAndMarksTheLink() {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        String user = enrolled(uid);
        // a core session exists so suspend must kill it
        var session = new com.mipay.sme.domain.core.DeviceSession();
        session.setUserId(user);
        session.setDeviceFingerprint("sweep-device");
        session.setTokenHash("hash-" + user);
        sessions.save(session);

        lifecycle.suspend(user, "sweep-test");

        User suspended = users.findById(user).orElseThrow();
        assertEquals(Enums.UserStatus.SUSPENDED, suspended.getStatus(), "core-first: refused on the next request");
        assertTrue(sessions.findByUserId(user).isEmpty(), "core sessions killed");

        processor.tick();
        var commands = outboxRepo.findAll().stream().filter(r -> user.equals(r.getUserId())).toList();
        assertTrue(commands.stream().anyMatch(r -> "DISABLE".equals(r.getCommand()) && r.getState() == AuthOutbox.State.DONE),
                "DISABLE ran against the real IdP: " + commands);
        assertTrue(commands.stream().anyMatch(r -> "REVOKE_SESSIONS".equals(r.getCommand()) && r.getState() == AuthOutbox.State.DONE));
        assertEquals(IdentityLink.Status.DISABLED, linkOf(user).getStatus(), "processor marked the link DISABLED");
        assertEquals(Enums.UserStatus.SUSPENDED, users.findById(user).orElseThrow().getStatus(),
                "a DISABLE confirming does not revive a suspended core user");
    }

    @Test
    void reinstateReenablesTheIdpAccountAndTheLink() {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        String user = enrolled(uid);
        lifecycle.suspend(user, "sweep-test");
        processor.tick();
        assertEquals(IdentityLink.Status.DISABLED, linkOf(user).getStatus());

        lifecycle.reinstate(user, "sweep-test");
        assertEquals(Enums.UserStatus.ACTIVE, users.findById(user).orElseThrow().getStatus());
        processor.tick();
        assertTrue(outboxRepo.findAll().stream().anyMatch(r -> user.equals(r.getUserId())
                && "ENABLE".equals(r.getCommand()) && r.getState() == AuthOutbox.State.DONE),
                "ENABLE ran against the real IdP");
        assertEquals(IdentityLink.Status.ACTIVE, linkOf(user).getStatus(), "link restored");

        lifecycle.archive(users.findById(user).orElseThrow(), "sweep");
        assertThrows(Exception.class, () -> lifecycle.reinstate(user, "sweep-test"),
                "archived users cannot be reinstated");
        assertEquals(Enums.UserStatus.ARCHIVED, users.findById(user).orElseThrow().getStatus(),
                "the refused reinstate did not un-archive");
    }

    @Test
    void archiveArchivesTheCoreAndRevokesTheIdpAccount() {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        String user = enrolled(uid);

        lifecycle.archive(users.findById(user).orElseThrow(), "sweep-test");
        assertEquals(Enums.UserStatus.ARCHIVED, users.findById(user).orElseThrow().getStatus(),
                "gateway mode writes ARCHIVED, not the legacy SUSPENDED");

        processor.tick();
        var commands = outboxRepo.findAll().stream().filter(r -> user.equals(r.getUserId())).toList();
        assertTrue(commands.stream().anyMatch(r -> "UNLINK".equals(r.getCommand()) && r.getState() == AuthOutbox.State.DONE),
                "UNLINK ran against the real IdP: " + commands);
        assertTrue(commands.stream().anyMatch(r -> "DISABLE".equals(r.getCommand()) && r.getState() == AuthOutbox.State.DONE));
        assertEquals(IdentityLink.Status.REVOKED, linkOf(user).getStatus(), "processor marked the link REVOKED");
    }

    @Test
    void preBindingUserIsEnrolledWhenItIsBoundToItsHomeOrg() {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        String phone = "+264834" + Math.abs(uid.hashCode()) % 10000000;
        // new-model self-registration in one transaction: the entity org exists before the
        // user, so the user is born bound (users.org_id is NOT NULL, V15_1) and the enrol
        // enqueues from lifecycle.create
        String boundId = txManager().execute(status ->
                lifecycle.create(new UserLifecycleService.Registration(rootId(), "Bound Sweep " + uid,
                        null, phone, null, Enums.UserRole.MERCHANT, Enums.OrgMemberRole.ADMIN, "sweep-it")).getId());
        assertTrue(users.existsByPhone(phone));
        // the self-registration path's ensureEntityOrg re-binds on every access path:
        // a no-op, still exactly one enrolment
        lifecycle.bindToOrg(boundId, rootId(), Enums.OrgMemberRole.ADMIN, "sweep-it");
        assertEquals(1, outboxRepo.findAll().stream()
                .filter(r -> boundId.equals(r.getUserId()) && "ENROL".equals(r.getCommand())).count());

        AuthOutbox enrolRow = outboxRepo.findAll().stream()
                .filter(r -> boundId.equals(r.getUserId()) && "ENROL".equals(r.getCommand()))
                .findFirst().orElseThrow();
        assertEquals("enrol:" + boundId, dedupeKey(enrolRow.getId()),
                "the binding enqueued the enrolment");
        assertEquals(Enums.UserStatus.PENDING, users.findById(boundId).orElseThrow().getStatus(),
                "no credential set-up yet: the user stays PENDING");
        assertNotNull(users.findById(boundId).orElseThrow().getUsername(),
                "a login name is generated for enrolment");

        // the IdP proves the whole chain works: the queued enrol completes against it
        processor.tick();
        enrolRow = outboxRepo.findAll().stream()
                .filter(r -> boundId.equals(r.getUserId()) && "ENROL".equals(r.getCommand()))
                .findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.DONE, enrolRow.getState(), enrolRow.getLastError());
        assertNotNull(linkOf(boundId).getSubject());

    }

    private String dedupeKey(String outboxId) {
        return jdbc.queryForObject("SELECT dedupe_key FROM auth_outbox WHERE id = ?", String.class, outboxId);
    }

    @AfterAll
    static void dropDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_writers WITH (FORCE)");
        } catch (Exception ignored) { }
    }
}
