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
}
