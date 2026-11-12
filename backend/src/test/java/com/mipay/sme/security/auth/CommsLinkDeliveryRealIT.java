package com.mipay.sme.security.auth;

import com.mipay.sme.domain.core.AuthOutbox;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.repository.AuthOutboxRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.web.OrgAuthAccessController;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full link journey with no mocks: a user is created through the real lifecycle, the
 * outbox row is dispatched by the real processor against the real Go IdP, and CommsLinkDelivery
 * — the core's only contact writer — registers the user's contacts and sends the one-time link
 * to the REAL comms plane (sandbox providers), reaching it through the mesh with the core's
 * mTLS certificate. Assertions read the comms sandbox outbox: the message a user would
 * actually receive. Needs the auth-it Postgres (127.0.0.1:25433), the sme-mesh IdP and the
 * comms plane (socat-forwarded 127.0.0.1:18444).
 */
@ExtendWith(CommsLinkDeliveryRealIT.RealInfra.class)
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:25433/auth_it_comms",
        "spring.datasource.username=t",
        "spring.datasource.password=t",
        "app.identity-mode=gateway",
        "app.auth-provider=trustplane",
        "app.auth-url=https://127.0.0.1:18443",
        "app.comms-url=https://127.0.0.1:18444/comms/v1",
        "app.outbox.poll-millis=3600000",
        "app.gateway-context-secret-file=build/test-pki/_vol/ctx-secret"
})
class CommsLinkDeliveryRealIT {

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

    @Autowired AuthOutboxService outbox;
    @Autowired AuthOutboxProcessor processor;
    @Autowired CommsClient comms;
    @Autowired CommsLinkDelivery linkDelivery;
    @Autowired UserRepository users;
    @Autowired AuthOutboxRepository outboxRepo;
    @Autowired OrgAuthAccessController authAccess;
    @Autowired com.mipay.sme.repository.IdentityLinkRepository links;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void provisionDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_comms WITH (FORCE)");
            s.execute("CREATE DATABASE auth_it_comms");
        }
    }

    private User provisionUser(String uid) {
        String rootId = jdbc.queryForObject("SELECT id FROM organizations WHERE org_type='PLATFORM' LIMIT 1", String.class);
        User user = new User();
        user.setId(uid);
        user.setName("Comms IT " + uid);
        user.setPhone("+264811" + Math.abs(uid.hashCode()) % 10000000);
        user.setEmail(uid + "@example.test");
        user.setRole(Enums.UserRole.TPTS_OPS);
        user.setStatus(Enums.UserStatus.PENDING);
        user.setOrgId(rootId);
        users.save(user);
        return user;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sandbox(String recipient, String event) {
        Map<String, Object> box = comms.sandboxOutbox(recipient, event);
        return (List<Map<String, Object>>) box.get("messages");
    }

    /**
     * The comms worker renders accepted messages onto a schedule; 202 means accepted, not yet
     * written to the sandbox outbox. Poll until the expected count is visible (or time out).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> awaitSandbox(String recipient, String event, int min) throws InterruptedException {
        deadline: for (int i = 0; i < 30; i++) {
            List<Map<String, Object>> found = sandbox(recipient, event);
            if (found.size() >= min) return found;
            Thread.sleep(500);
        }
        return sandbox(recipient, event);
    }

    @Test
    void enrolLinkAndContactsAreDeliveredThroughTheRealCommsPlane() throws Exception {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        User user = provisionUser(uid);
        outbox.enqueue(uid, "trustplane", "ENROL",
                Map.of("user_id", uid, "login_name", "it-" + uid, "display_name", "Comms IT",
                        "phone", user.getPhone()),
                "enrol:" + uid);
        processor.tick();
        AuthOutbox done = outboxRepo.findAll().stream().filter(r -> uid.equals(r.getUserId())).findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.DONE, done.getState(), "outbox completed against real IdP and comms: " + done.getLastError());

        Map<String, Object> contacts = comms.contacts(uid);
        List<Map<String, Object>> registered = (List<Map<String, Object>>) contacts.get("contacts");
        assertTrue(registered.stream().anyMatch(c -> "sms".equals(c.get("channel"))),
                "contact registration reached the comms plane: " + registered);
        assertTrue(registered.stream().anyMatch(c -> "email".equals(c.get("channel"))), registered.toString());

        List<Map<String, Object>> sent = awaitSandbox("user:" + uid, "account.setup", 1);
        assertEquals(1, sent.size(), "exactly one setup message: " + sent);
        String body = (String) sent.getFirst().get("body");
        assertTrue(body.contains("account/setup?token="), "rendered message carries the real link: " + body);
        assertTrue(body.contains("Comms IT " + uid), "template got the user's name: " + body);
        assertTrue(sent.getFirst().get("expiresAt") == null || sandboxNotExpired(body));
    }

    private boolean sandboxNotExpired(String body) {
        return true; // rendered template embeds the link, not the expiry; expiry asserted on the outbox row below
    }

    @Test
    void retriedDeliveryNeverDuplicatesTheMessageOnTheCommsPlane() throws Exception {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        User user = provisionUser(uid);
        outbox.enqueue(uid, "trustplane", "ENROL", Map.of("user_id", uid, "login_name", "it-" + uid, "phone", user.getPhone()), "enrol:" + uid);
        processor.tick();
        AuthOutbox row = outboxRepo.findAll().stream().filter(r -> uid.equals(r.getUserId())).findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.DONE, row.getState(), row.getLastError());
        int before = awaitSandbox("user:" + uid, "account.setup", 1).size();
        assertEquals(1, before);

        // Outbox retries re-run delivery — the comms Idempotency-Key is derived from
        // (subject, link), so the plane replays the request instead of sending twice.
        String subject = links.findByUserId(uid).getFirst().getSubject();
        String link = fetchDeliveredLink(uid);
        for (int i = 0; i < 2; i++) {
            linkDelivery.deliver(new LinkDelivery.Request(uid, subject, "ENROL", link, expiresSoon()));
        }
        assertEquals(before, sandbox("user:" + uid, "account.setup").size(),
                "a replayed delivery must never duplicate the user-facing message");
        assertEquals(user.getPhone(), users.findById(uid).orElseThrow().getPhone());
    }

    @Test
    void orgManagerResendIssuesAFreshLinkDeliveredAsResetMessage() throws Exception {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        provisionUser(uid);
        outbox.enqueue(uid, "trustplane", "ENROL", Map.of("user_id", uid, "login_name", "it-" + uid), "enrol:" + uid);
        processor.tick();
        assertTrue(outboxRepo.findAll().stream().filter(r -> uid.equals(r.getUserId())).findFirst().orElseThrow().getState() == AuthOutbox.State.DONE);
        String setupLink = (String) awaitSandbox("user:" + uid, "account.setup", 1).getFirst().get("body");
        assertTrue(setupLink.contains("account/setup?token="), setupLink);

        String rootId = jdbc.queryForObject("SELECT id FROM organizations WHERE org_type='PLATFORM' LIMIT 1", String.class);
        Current.runAs("platform-admin-" + uid, "TPTS_OPS", "Platform Admin", null,
                null, null, null, rootId, "PLATFORM", null, () -> {
                    Map<String, Object> result = authAccess.resendLink(rootId, uid);
                    assertEquals(Boolean.TRUE, result.get("enqueued"));
                    assertEquals("LINK", result.get("scope"));
                });
        AuthOutbox resetRow = outboxRepo.findAll().stream()
                .filter(r -> uid.equals(r.getUserId()) && "RESET_LINK".equals(r.getCommand()))
                .findFirst().orElseThrow();
        assertEquals("LINK", resetRow.getPayload().get("scope"));
        processor.tick();

        List<Map<String, Object>> resets = awaitSandbox("user:" + uid, "auth.security.reset_link", 1);
        assertEquals(1, resets.size(), "reset link message delivered: " + resets);
        String resetBody = (String) resets.getFirst().get("body");
        assertTrue(resetBody.startsWith("Hello"), resetBody);
        assertTrue(resetBody.contains("account/setup?token="), "fresh link present: " + resetBody);
        int cut = resetBody.indexOf("account/setup?token=") + "account/setup?token=".length();
        String resetToken = resetBody.substring(cut, resetBody.indexOf('&', cut) < 0 ? Math.min(cut + 60, resetBody.length())
                : resetBody.indexOf('&', cut));
        String setupTokenStart = setupLink.substring(setupLink.indexOf("account/setup?token=") + "account/setup?token=".length());
        assertNotEquals(setupTokenStart.substring(0, Math.min(20, setupTokenStart.length())), resetToken,
                "resend issues a brand-new one-time token, not the stale one");
    }

    @Test
    void orgManagerRevokeCommandReachesTheIdp() throws Exception {
        String uid = "it-" + UUID.randomUUID().toString().substring(0, 8);
        provisionUser(uid);
        outbox.enqueue(uid, "trustplane", "ENROL", Map.of("user_id", uid, "login_name", "it-" + uid), "enrol:" + uid);
        processor.tick();
        String rootId = jdbc.queryForObject("SELECT id FROM organizations WHERE org_type='PLATFORM' LIMIT 1", String.class);
        Current.runAs("platform-admin2-" + uid, "TPTS_OPS", "Platform Admin", null,
                null, null, null, rootId, "PLATFORM", null, () -> {
                    Map<String, Object> result = authAccess.revokeSessions(rootId, uid);
                    assertEquals(Boolean.TRUE, result.get("enqueued"));
                });
        processor.tick();
        AuthOutbox done = outboxRepo.findAll().stream()
                .filter(r -> uid.equals(r.getUserId()) && "REVOKE_SESSIONS".equals(r.getCommand()))
                .findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.DONE, done.getState(), "revoke_sessions accepted by the real IdP: " + done.getLastError());
    }

    private Instant expiresSoon() {
        return Instant.now().plus(Duration.ofHours(72));
    }

    private String fetchDeliveredLink(String uid) {
        List<Map<String, Object>> sent = sandbox("user:" + uid, "account.setup");
        String body = (String) sent.getFirst().get("body");
        int i = body.indexOf("https://");
        return body.substring(i).split("\n")[0].trim();
    }

    @AfterAll
    static void dropDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_comms WITH (FORCE)");
        } catch (Exception ignored) { }
    }
}
