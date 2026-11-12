package trustplane.comms;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import trustplane.shared.PostgresDsn;

/** Scratch database test: TEST_COMMS_DSN must point to a disposable database. */
class CommsIntegrationTest {
    @Test void encryptedQueueDeliversAndAbsentProviderSuppresses() throws Exception {
        String raw = System.getenv("TEST_COMMS_DSN");
        assumeTrue(raw != null && !raw.isBlank(), "TEST_COMMS_DSN not set");
        PostgresDsn dsn = PostgresDsn.parse(raw);
        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl(dsn.jdbcUrl()); ds.setUsername(dsn.user()); ds.setPassword(dsn.password());
            JdbcTemplate db = new JdbcTemplate(ds);
            for (String table : List.of("comms_java_jobs","sim_outbox","message_events","messages","requests","contacts","tenants"))
                db.execute("DROP TABLE IF EXISTS " + table + " CASCADE");
            new Schema(db);
            var otp = new CommsConfig.Policy("SECURITY", "CRITICAL", List.of("sms"), Duration.ofMinutes(5), true, List.of("code"));
            var template = new CommsConfig.Template("auth.otp.sms", "sms", "en", "", "Code {{code}}");
            var cfg = new CommsConfig.Settings("dev", Map.of("idp", List.of("auth."), "mipay", List.of("account.")),
                    List.of("mipay"), Map.of("sms", "sandbox"), Map.of("auth.otp.sms", otp), List.of(template));
            var service = new CommsService(db, new Sealer("k".repeat(32).getBytes(StandardCharsets.UTF_8)), cfg);
            var worker = new CommsWorker(service);
            assertThrows(ResponseStatusException.class, () -> service.putContacts("idp", "user:one",
                    new CommsService.Contacts(List.of(new CommsService.Contact("sms", "+264810000042", true, true)))));
            service.putContacts("mipay", "user:one", new CommsService.Contacts(List.of(
                    new CommsService.Contact("sms", "+264810000042", true, true))));
            var send = new CommsService.Send("auth.otp.sms", List.of("user:one"), Map.of("code","739201"),
                    "CRITICAL", Instant.now().plusSeconds(300), "SECRET", "en");
            assertThrows(ResponseStatusException.class, () -> service.send("mipay", send, "not-allowed"));
            var accepted = service.send("idp", send, "same-key");
            assertEquals(accepted.get("requestId"), service.send("idp", send, "same-key").get("requestId"));
            assertEquals(1L, db.queryForObject("SELECT count(*) FROM messages", Long.class));
            byte[] sealed = db.queryForObject("SELECT payload FROM comms_java_jobs", byte[].class);
            assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("739201"));
            worker.drain();
            assertEquals("DELIVERED", db.queryForObject("SELECT status FROM messages", String.class));
            assertEquals("Code 739201", db.queryForObject("SELECT body FROM sim_outbox", String.class));
            assertEquals(0L, db.queryForObject("SELECT count(*) FROM comms_java_jobs", Long.class));

            var noProvider = new CommsConfig.Settings("uat", cfg.callers(), cfg.contactWriters(), Map.of(), cfg.policies(), cfg.templates());
            var absent = new CommsService(db, new Sealer("k".repeat(32).getBytes(StandardCharsets.UTF_8)), noProvider);
            var missing = absent.send("idp", new CommsService.Send("auth.otp.sms", List.of("user:two"),
                    Map.of("code","999999"), "CRITICAL", Instant.now().plusSeconds(300), "SECRET", "en"), "other-key");
            new CommsWorker(absent).drain();
            String missingId = ((Map<String,String>) ((List<?>) missing.get("messages")).getFirst()).get("id");
            assertEquals("SUPPRESSED", absent.message("idp", missingId).get("status"));
            assertEquals(1L, db.queryForObject("SELECT count(*) FROM sim_outbox", Long.class));
        }
    }

    /** B1: a named real provider takes the email instead of sim_outbox and records its id. */
    @Test void realEmailProviderDeliversWithoutSandboxRow() throws Exception {
        String raw = System.getenv("TEST_COMMS_DSN");
        assumeTrue(raw != null && !raw.isBlank(), "TEST_COMMS_DSN not set");
        PostgresDsn dsn = PostgresDsn.parse(raw);
        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl(dsn.jdbcUrl()); ds.setUsername(dsn.user()); ds.setPassword(dsn.password());
            JdbcTemplate db = new JdbcTemplate(ds);
            for (String table : List.of("comms_java_jobs","sim_outbox","message_events","messages","requests","contacts","tenants"))
                db.execute("DROP TABLE IF EXISTS " + table + " CASCADE");
            new Schema(db);
            var notice = new CommsConfig.Policy("SECURITY", "CRITICAL", List.of("email"), Duration.ofHours(24), false, List.of());
            var template = new CommsConfig.Template("auth.security.notice", "email", "en", "MiPay security notice", "Hello {{name}}, {{message}}");
            var cfg = new CommsConfig.Settings("uat", Map.of("mipay", List.of("auth.")), List.of("mipay"),
                    Map.of("email", "brevo"), Map.of("auth.security.notice", notice), List.of(template));
            var service = new CommsService(db, new Sealer("k".repeat(32).getBytes(StandardCharsets.UTF_8)), cfg);
            // the real sender is faked: no sockets in tests (comms-design B0 note)
            java.util.List<String> sentTo = new java.util.ArrayList<>();
            var worker = new CommsWorker(service, (to, toName, subject, body) -> {
                sentTo.add(to); return "brevo:<202@smtp-relay>";
            });
            service.putContacts("mipay", "user:one", new CommsService.Contacts(List.of(
                    new CommsService.Contact("email", "user@example.na", true, true))));
            var send = new CommsService.Send("auth.security.notice", List.of("user:one"),
                    Map.of("name","Amina","message","Contact us if this was not you."),
                    "CRITICAL", null, "CONFIDENTIAL", "en");
            var accepted = service.send("mipay", send, null);
            String id = ((Map<String,String>) ((List<?>) accepted.get("messages")).getFirst()).get("id");
            worker.drain();
            Map<String,Object> row = db.queryForMap("SELECT status,provider_id,address_masked FROM messages WHERE id=?", id);
            assertEquals("DELIVERED", row.get("status"));
            assertEquals("brevo:<202@smtp-relay>", row.get("provider_id"));
            assertEquals(0L, db.queryForObject("SELECT count(*) FROM sim_outbox", Long.class),
                    "a real provider must not write the sandbox outbox");
            assertEquals(List.of("user@example.na"), sentTo);
        }
    }

    /** B1: a failing provider retries with back-off and lands FAILED, never silently delivered. */
    @Test void failingRealProviderRetriesThenFails() throws Exception {
        String raw = System.getenv("TEST_COMMS_DSN");
        assumeTrue(raw != null && !raw.isBlank(), "TEST_COMMS_DSN not set");
        PostgresDsn dsn = PostgresDsn.parse(raw);
        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl(dsn.jdbcUrl()); ds.setUsername(dsn.user()); ds.setPassword(dsn.password());
            JdbcTemplate db = new JdbcTemplate(ds);
            for (String table : List.of("comms_java_jobs","sim_outbox","message_events","messages","requests","contacts","tenants"))
                db.execute("DROP TABLE IF EXISTS " + table + " CASCADE");
            new Schema(db);
            var notice = new CommsConfig.Policy("SECURITY", "CRITICAL", List.of("email"), Duration.ofHours(24), false, List.of());
            var template = new CommsConfig.Template("auth.security.notice", "email", "en", "MiPay security notice", "{{message}}");
            var cfg = new CommsConfig.Settings("uat", Map.of("mipay", List.of("auth.")), List.of("mipay"),
                    Map.of("email", "brevo"), Map.of("auth.security.notice", notice), List.of(template));
            var service = new CommsService(db, new Sealer("k".repeat(32).getBytes(StandardCharsets.UTF_8)), cfg);
            int[] calls = {0};
            var worker = new CommsWorker(service, (to, toName, subject, body) -> {
                calls[0]++; throw new IllegalStateException("brevo email refused: HTTP 500");
            });
            service.putContacts("mipay", "user:one", new CommsService.Contacts(List.of(
                    new CommsService.Contact("email", "user@example.na", true, true))));
            var accepted = service.send("mipay", new CommsService.Send("auth.security.notice", List.of("user:one"),
                    Map.of("message","x"), "CRITICAL", null, "CONFIDENTIAL", "en"), null);
            String id = ((Map<String,String>) ((List<?>) accepted.get("messages")).getFirst()).get("id");
            for (int attempt = 0; attempt < 4; attempt++) {
                worker.drain();
                db.update("UPDATE comms_java_jobs SET available_at=now()-interval '1 second', lease_until=NULL");
            }
            String status = (String) db.queryForObject("SELECT status FROM messages WHERE id=?", String.class, id);
            String reason = (String) db.queryForObject("SELECT status_reason FROM messages WHERE id=?", String.class, id);
            assertEquals("FAILED", status, reason);
            assertTrue(calls[0] >= 3, "provider called up to the attempt cap");
            assertEquals(0L, db.queryForObject("SELECT count(*) FROM sim_outbox", Long.class));
        }
    }
}
