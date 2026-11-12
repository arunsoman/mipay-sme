package trustplane.comms;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommsService {
    private static final Pattern SUBJECT = Pattern.compile("^user:[A-Za-z0-9._:@-]{1,128}$");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private final JdbcTemplate db;
    private final Sealer sealer;
    private final CommsConfig.Settings cfg;
    private final ObjectMapper json = new ObjectMapper();

    public CommsService(JdbcTemplate db, Sealer sealer, CommsConfig.Settings cfg) {
        this.db = db;
        this.sealer = sealer;
        this.cfg = cfg;
    }

    public record Send(String eventCode, List<String> to, Map<String,String> data, String lane,
                       Instant expiresAt, String sensitivity, String locale) {}
    public record Contact(String channel, String address, boolean verified, boolean primary) {}
    public record Contacts(List<Contact> contacts) {}

    static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
    static ResponseStatusException bad(HttpStatus status, String message) { return new ResponseStatusException(status, message); }

    @Transactional
    public Map<String,Object> send(String caller, Send body, String idem) throws Exception {
        if (body == null || body.eventCode() == null) throw bad(HttpStatus.BAD_REQUEST, "eventCode required");
        var policy = cfg.policies().get(body.eventCode());
        if (policy == null) throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "unknown eventCode");
        if (cfg.callers().getOrDefault(caller, List.of()).stream().noneMatch(body.eventCode()::startsWith))
            throw bad(HttpStatus.FORBIDDEN, "event not allowed for caller");
        if (body.to() == null || body.to().isEmpty() || body.to().size() > 100 ||
                body.to().stream().anyMatch(to -> to == null || !SUBJECT.matcher(to).matches()))
            throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "to must contain 1-100 user:<id> recipients");
        if (body.lane() != null && !body.lane().isBlank() && !body.lane().equals(policy.lane()))
            throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "lane does not match event policy");
        if (idem != null && idem.length() > 200) throw bad(HttpStatus.BAD_REQUEST, "Idempotency-Key too long");
        Instant expires = body.expiresAt();
        if (policy.requireExpiry() && expires == null) throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "expiresAt required");
        if (expires == null && !policy.ttl().isZero()) expires = Instant.now().plus(policy.ttl());
        if (expires != null && !expires.isAfter(Instant.now())) throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "message expired");
        Map<String,String> data = body.data() == null ? Map.of() : body.data();
        for (var template : cfg.templates()) {
            if (!template.event().equals(body.eventCode()) || !policy.channels().contains(template.channel())) continue;
            Matcher matcher = VARIABLE.matcher(template.subject() + template.body());
            while (matcher.find()) if (data.getOrDefault(matcher.group(1), "").isBlank())
                throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "missing template variable: " + matcher.group(1));
        }
        if (idem != null && !idem.isBlank()) {
            List<String> existing = db.queryForList("SELECT id FROM requests WHERE tenant_id='platform' AND client_id=? AND idempotency_key=?", String.class, caller, idem);
            if (!existing.isEmpty()) return request(caller, existing.getFirst());
        }
        String req = id("req");
        int inserted = db.update("""
                INSERT INTO requests(id,tenant_id,client_id,idempotency_key,event_code,lane,recipients)
                VALUES(?,'platform',?,?,?,?,?) ON CONFLICT DO NOTHING
                """, req, caller, idem == null || idem.isBlank() ? null : idem, body.eventCode(), policy.lane(), body.to().size());
        if (inserted == 0) {
            List<String> existing = db.queryForList("SELECT id FROM requests WHERE tenant_id='platform' AND client_id=? AND idempotency_key=?", String.class, caller, idem);
            if (!existing.isEmpty()) return request(caller, existing.getFirst());
            throw bad(HttpStatus.CONFLICT, "Idempotency-Key unavailable");
        }
        byte[] sealed = sealer.seal(json.writeValueAsBytes(data));
        int seq = 0;
        for (String recipient : body.to()) {
            String msg = id("msg");
            db.update("""
                    INSERT INTO messages(id,request_id,tenant_id,client_id,event_code,recipient_ref,lane,category,
                        sensitivity,locale,data_names,status,expires_at,seq)
                    VALUES(?,?,'platform',?,?,?,?,?,?,?,?,'ACCEPTED',?,?)
                    """, msg, req, caller, body.eventCode(), recipient, policy.lane(), policy.category(),
                    policy.secret().isEmpty() ? "NORMAL" : "SECRET", body.locale() == null ? "en" : body.locale(),
                    data.keySet().toArray(String[]::new), expires == null ? null : Timestamp.from(expires), seq++);
            db.update("INSERT INTO message_events(message_id,status) VALUES(?,'ACCEPTED')", msg);
            db.update("INSERT INTO comms_java_jobs(message_id,payload) VALUES(?,?)", msg, sealed);
        }
        return request(caller, req);
    }

    public Map<String,Object> request(String caller, String id) {
        List<String> owners = db.queryForList("SELECT client_id FROM requests WHERE id=?", String.class, id);
        if (owners.isEmpty() || !owners.getFirst().equals(caller)) throw bad(HttpStatus.NOT_FOUND, "unknown request");
        return Map.of("requestId", id, "messages", db.queryForList(
                "SELECT id, recipient_ref AS to, status FROM messages WHERE request_id=? ORDER BY seq", id));
    }

    public Map<String,Object> message(String caller, String id) {
        List<Map<String,Object>> found = db.queryForList("""
                SELECT id, request_id AS "requestId", event_code AS "eventCode", recipient_ref AS "to",
                    lane, status, status_reason AS reason, channel, address_masked AS address,
                    expires_at AS "expiresAt", created_at AS "createdAt", updated_at AS "updatedAt", client_id
                FROM messages WHERE id=?
                """, id);
        if (found.isEmpty() || !caller.equals(found.getFirst().get("client_id"))) throw bad(HttpStatus.NOT_FOUND, "unknown message");
        Map<String,Object> result = new HashMap<>(found.getFirst());
        result.remove("client_id");
        return result;
    }

    public Map<String,Object> events(String caller, String id) {
        message(caller, id);
        return Map.of("messageId", id, "events", db.queryForList(
                "SELECT at,status,detail::text AS detail FROM message_events WHERE message_id=? ORDER BY id", id));
    }

    @Transactional
    public Map<String,Object> putContacts(String caller, String subject, Contacts body) throws Exception {
        if (!cfg.contactWriters().contains(caller)) throw bad(HttpStatus.FORBIDDEN, "only core writes contacts");
        if (!SUBJECT.matcher(subject).matches()) throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "invalid subject");
        if (body == null || body.contacts() == null || body.contacts().size() > 10)
            throw bad(HttpStatus.BAD_REQUEST, "contacts must contain at most ten entries");
        List<ContactRow> normalized = new ArrayList<>();
        for (Contact contact : body.contacts()) {
            String address = normalize(contact.channel(), contact.address());
            normalized.add(new ContactRow(contact.channel(), address, sealer.seal(address.getBytes(StandardCharsets.UTF_8)),
                    sealer.blindIndex(contact.channel(), address), mask(contact.channel(), address), contact.verified(), contact.primary()));
        }
        db.update("DELETE FROM contacts WHERE tenant_id='platform' AND subject_ref=?", subject);
        for (ContactRow row : normalized) db.update("""
                INSERT INTO contacts(tenant_id,subject_ref,channel,address_enc,address_hash,address_masked,verified_at,is_primary,updated_by)
                VALUES('platform',?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, subject, row.channel(), row.encrypted(), row.index(), row.masked(),
                row.verified() ? Timestamp.from(Instant.now()) : null, row.primary(), caller);
        return contacts(caller, subject);
    }

    private record ContactRow(String channel, String address, byte[] encrypted, String index,
                              String masked, boolean verified, boolean primary) {}

    public Map<String,Object> contacts(String caller, String subject) {
        if (!cfg.contactWriters().contains(caller)) throw bad(HttpStatus.FORBIDDEN, "only core reads contacts");
        return Map.of("subject", subject, "contacts", db.queryForList("""
                SELECT channel,address_masked AS address,verified_at IS NOT NULL AS verified,is_primary AS "primary"
                FROM contacts WHERE tenant_id='platform' AND subject_ref=? ORDER BY channel,is_primary DESC
                """, subject));
    }

    static String normalize(String channel, String address) {
        if (channel == null || address == null) throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "invalid contact");
        address = address.strip();
        if (channel.equals("email")) {
            address = address.toLowerCase();
            if (!address.matches("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$"))
                throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "invalid email address");
        } else if (channel.equals("sms")) {
            address = address.replaceAll("[ ()-]", "");
            if (!address.matches("\\+?[0-9]{6,15}")) throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "invalid phone number");
        } else throw bad(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported contact channel");
        return address;
    }

    static String mask(String channel, String address) {
        if (channel.equals("email")) return address.charAt(0) + "***" + address.substring(address.indexOf('@'));
        return address.length() <= 6 ? "***" : address.substring(0, 4) + "***" + address.substring(address.length() - 4);
    }

    public Map<String,Object> capabilities() {
        Map<String,Object> channels = new HashMap<>();
        for (String channel : List.of("sms", "email", "push", "whatsapp", "in_app")) {
            List<String> missing = new ArrayList<>();
            cfg.policies().forEach((event, policy) -> {
                if (!event.startsWith("auth.") || !policy.channels().contains(channel)) return;
                if (cfg.templates().stream().noneMatch(t -> t.event().equals(event) && t.channel().equals(channel))) missing.add(event);
            });
            channels.put(channel, Map.of("live", cfg.providers().containsKey(channel) && missing.isEmpty(),
                    "provider", cfg.providers().getOrDefault(channel, ""), "missingTemplates", missing));
        }
        return Map.of("environment", cfg.environment(), "channels", channels);
    }

    public Map<String,Object> sandboxOutbox(String caller, String recipient, String event) {
        if (!cfg.sandbox()) throw bad(HttpStatus.NOT_FOUND, "sandbox unavailable");
        if (!cfg.contactWriters().contains(caller) && !caller.equals("idp")) throw bad(HttpStatus.FORBIDDEN, "sandbox access denied");
        return Map.of("messages", db.queryForList("""
                SELECT id,message_id AS "messageId",event_code AS "eventCode",recipient_ref AS "to",
                    channel,address,subject,body,created_at AS "createdAt" FROM sim_outbox
                WHERE (?='' OR recipient_ref=?) AND (?='' OR event_code=?) ORDER BY created_at DESC LIMIT 50
                """, recipient, recipient, event, event));
    }

    String render(CommsConfig.Template template, Map<String,String> data) {
        Matcher matcher = VARIABLE.matcher(template.body());
        return matcher.replaceAll(m -> Matcher.quoteReplacement(data.getOrDefault(m.group(1), "")));
    }

    @SuppressWarnings("unchecked")
    Map<String,String> unseal(byte[] payload) throws GeneralSecurityException {
        return (Map<String,String>) json.readValue(sealer.open(payload), Map.class);
    }

    CommsConfig.Settings config() { return cfg; }
    JdbcTemplate db() { return db; }
    Sealer sealer() { return sealer; }
}
