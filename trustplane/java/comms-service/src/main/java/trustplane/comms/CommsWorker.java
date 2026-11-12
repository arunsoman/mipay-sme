package trustplane.comms;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Leased Postgres queue. The payload stays encrypted until rendering in memory. */
@Component
public class CommsWorker {
    private static final Logger log = LoggerFactory.getLogger(CommsWorker.class);
    private final CommsService service;
    private final JdbcTemplate db;
    private final EmailSender emailSender;

    /** The production constructor (two constructors exist, so Spring must be told which). */
    @org.springframework.beans.factory.annotation.Autowired
    public CommsWorker(CommsService service) {
        this(service, BrevoEmailSender.create(service.config()));
    }

    /** Test seam: a fake sender for real-provider flows. */
    public CommsWorker(CommsService service, EmailSender emailSenderOverride) {
        this.service = service;
        this.emailSender = emailSenderOverride != null ? emailSenderOverride
                : BrevoEmailSender.create(service.config());
        this.db = service.db();
    }

    @Scheduled(fixedDelay = 250)
    public void drain() {
        for (int i = 0; i < 20; i++) {
            List<Map<String,Object>> leased = db.queryForList("""
                    UPDATE comms_java_jobs SET lease_until=now()+interval '30 seconds',attempts=attempts+1
                    WHERE message_id IN (SELECT message_id FROM comms_java_jobs
                        WHERE available_at<=now() AND (lease_until IS NULL OR lease_until<now())
                        ORDER BY available_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                    RETURNING message_id,payload,attempts
                    """);
            if (leased.isEmpty()) return;
            for (Map<String,Object> job : leased) {
                String id = (String) job.get("message_id");
                try { deliver(id, (byte[]) job.get("payload")); }
                catch (Exception e) { retry(id, (Integer) job.get("attempts"), e); }
            }
        }
    }

    private void deliver(String id, byte[] payload) throws Exception {
        List<Map<String,Object>> rows = db.queryForList("""
                SELECT event_code,recipient_ref,locale,expires_at,status FROM messages WHERE id=?
                """, id);
        if (rows.isEmpty()) { db.update("DELETE FROM comms_java_jobs WHERE message_id=?", id); return; }
        Map<String,Object> m = rows.getFirst();
        if (!List.of("ACCEPTED", "SENDING").contains(m.get("status"))) { db.update("DELETE FROM comms_java_jobs WHERE message_id=?", id); return; }
        Timestamp expires = (Timestamp) m.get("expires_at");
        if (expires != null && !expires.toInstant().isAfter(Instant.now())) { terminal(id,"EXPIRED","expired before delivery"); return; }
        String event = (String) m.get("event_code"), ref = (String) m.get("recipient_ref");
        var policy = service.config().policies().get(event);
        Map<String,String> data = service.unseal(payload);
        for (String channel : policy.channels()) {
            if (!service.config().providers().containsKey(channel)) continue;
            List<Map<String,Object>> contacts = db.queryForList("""
                    SELECT address_enc,address_masked FROM contacts WHERE tenant_id='platform'
                    AND subject_ref=? AND channel=? ORDER BY is_primary DESC LIMIT 1
                    """, ref, channel);
            if (contacts.isEmpty()) continue;
            var template = service.config().templates().stream().filter(t -> t.event().equals(event)
                    && t.channel().equals(channel) && t.locale().equals(m.get("locale"))).findFirst().orElse(null);
            if (template == null) continue;
            String address = new String(service.sealer().open((byte[]) contacts.getFirst().get("address_enc")), StandardCharsets.UTF_8);
            String body = service.render(template, data);
            String subject = template.subject();
            for (var entry : data.entrySet()) subject = subject.replace("{{" + entry.getKey() + "}}", entry.getValue());
            if (expires != null && !expires.toInstant().isAfter(Instant.now())) { terminal(id,"EXPIRED","expired before dispatch"); return; }
            db.update("UPDATE messages SET status='SENDING',channel=?,address_masked=?,provider_id='sandbox',updated_at=now() WHERE id=?",
                    channel, contacts.getFirst().get("address_masked"), id);
            event(id, "SENDING");
            if (!"sandbox".equals(service.config().providers().get(channel))) {
                // B1: a real provider takes the message; sandbox keeps the H8 sim_outbox read
                if (emailSender == null)
                    throw new IllegalStateException("provider " + service.config().providers().get(channel)
                            + " named but not wired");
                String providerId = emailSender.send(address, null, subject, body);
                db.update("UPDATE messages SET provider_id=?,updated_at=now() WHERE id=?", providerId, id);
            } else {
                // Unique message id makes a lease retry safe after a successful sandbox insert.
                db.update("""
                        INSERT INTO sim_outbox(id,message_id,event_code,recipient_ref,channel,address,subject,body)
                        VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(message_id) DO NOTHING
                        """, CommsService.id("sim"), id, event, ref, channel, address, subject, body);
                if (service.config().sandbox()) log.info("[comms-sandbox] to={} event={} channel={} body={}", ref, event, channel, body);
            }
            db.update("UPDATE messages SET status='DELIVERED',updated_at=now() WHERE id=?", id);
            event(id, "DELIVERED");
            db.update("DELETE FROM comms_java_jobs WHERE message_id=?", id);
            return;
        }
        terminal(id, "SUPPRESSED", "no reachable contact or live provider");
    }

    private void terminal(String id, String status, String reason) {
        db.update("UPDATE messages SET status=?,status_reason=?,updated_at=now() WHERE id=?", status, reason, id);
        event(id, status);
        db.update("DELETE FROM comms_java_jobs WHERE message_id=?", id);
    }

    private void event(String id, String status) {
        db.update("INSERT INTO message_events(message_id,status) VALUES(?,?)", id, status);
    }

    private void retry(String id, int attempts, Exception error) {
        if (attempts >= 3) { terminal(id, "FAILED", "delivery failed after retries"); return; }
        long delay = Math.min(60, 1L << attempts);
        db.update("""
                UPDATE comms_java_jobs SET available_at=now()+(? * interval '1 second'),
                    lease_until=NULL,last_error=? WHERE message_id=?
                """, delay, error.getClass().getSimpleName(), id);
    }

    @Scheduled(fixedDelay = 600_000)
    public void sweepSandbox() {
        if (service.config().sandbox())
            db.update("DELETE FROM sim_outbox WHERE created_at < now() - interval '30 days'");
    }
}
