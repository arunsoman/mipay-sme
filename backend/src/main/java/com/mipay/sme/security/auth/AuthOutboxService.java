package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.domain.core.AuthOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enqueue (idempotent, atomic inside the caller's transaction), lease (SKIP LOCKED, safe with
 * several workers and across backend instances), complete, retry with bounded attempts, and
 * requeue. Durable commands are the only path from core state to provider actions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthOutboxService {
    static final int MAX_ATTEMPTS = 12;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record EnqueueResult(String id, boolean duplicate) { }

    /** Call inside the same transaction as the core change. Duplicate dedupe keys return the existing row. */
    public EnqueueResult enqueue(String userId, String provider, String command,
                                 Map<String, Object> payload, String dedupeKey) {
        String id = UUID.randomUUID().toString();
        try {
            String json = mapper.writeValueAsString(payload == null ? Map.of() : payload);
            int inserted = jdbc.update(
                    "INSERT INTO auth_outbox (id, user_id, provider, command, payload, dedupe_key) "
                            + "VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (dedupe_key) DO NOTHING",
                    id, userId, provider, command, json, dedupeKey);
            if (inserted == 1) return new EnqueueResult(id, false);
            String existing = jdbc.queryForObject(
                    "SELECT id FROM auth_outbox WHERE dedupe_key = ?", String.class, dedupeKey);
            return new EnqueueResult(existing, true);
        } catch (Exception e) {
            throw new IllegalStateException("outbox enqueue failed for " + command, e);
        }
    }

    /** Atomically claims ready rows (and expired leases after a worker crash) and marks them RUNNING. */
    public List<AuthOutbox> leaseBatch(int batch, Duration lease) {
        Instant until = Instant.now().plus(lease);
        String sql = """
                WITH ready AS (
                    SELECT id FROM auth_outbox
                     WHERE (state = 'PENDING' AND available_at <= ?)
                        OR (state = 'RUNNING' AND lease_until < ?)
                     ORDER BY available_at
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE auth_outbox o
                   SET state = 'RUNNING', attempts = o.attempts + 1, lease_until = ?
                  FROM ready
                 WHERE o.id = ready.id
                RETURNING o.id, o.user_id, o.provider, o.command, o.payload, o.state,
                          o.attempts, o.available_at, o.lease_until, o.created_at, o.dedupe_key
                """;
        return new ArrayList<>(jdbc.query(sql, (rs, i) -> {
            AuthOutbox row = new AuthOutbox();
            row.setId(rs.getString("id"));
            row.setUserId(rs.getString("user_id"));
            row.setProvider(rs.getString("provider"));
            row.setCommand(rs.getString("command"));
            row.setPayload(readPayload(rs.getString("payload")));
            row.setState(AuthOutbox.State.valueOf(rs.getString("state")));
            row.setAttempts(rs.getInt("attempts"));
            row.setAvailableAt(rs.getTimestamp("available_at").toInstant());
            row.setLeaseUntil(rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant());
            row.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            row.setDedupeKey(rs.getString("dedupe_key"));
            return row;
        }, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), batch, Timestamp.from(until)));
    }

    private Map<String, Object> readPayload(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json, mapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            throw new IllegalStateException("outbox payload is not valid JSON", e);
        }
    }

    /** Marks a leased command applied. If the lease was lost the update matches nothing; commands are idempotent. */
    public void complete(String id) {
        jdbc.update("UPDATE auth_outbox SET state = 'DONE', completed_at = now(), lease_until = NULL, "
                + "last_error = NULL WHERE id = ? AND state = 'RUNNING'", id);
    }

    /** A transient failure: back off (exponential, capped) until attempts run out, then mark FAILED. */
    public void fail(String id, String lastError) {
        Integer rowAttempts = jdbc.queryForObject(
                "SELECT attempts FROM auth_outbox WHERE id = ? AND state = 'RUNNING'",
                (rs, i) -> rs.getInt("attempts"), id);
        if (rowAttempts == null) {
            log.warn("auth outbox command {} lease lost; skipping failure handling", id);
            return; // another worker owns the outcome
        }
        boolean terminal = rowAttempts >= MAX_ATTEMPTS;
        String error = truncate(lastError);
        if (terminal) {
            jdbc.update("UPDATE auth_outbox SET state = 'FAILED', lease_until = NULL, last_error = ? "
                    + "WHERE id = ? AND state = 'RUNNING'", error, id);
        } else {
            long backoff = Math.min(5L * (1L << Math.min(rowAttempts - 1, 20)), 600);
            jdbc.update("UPDATE auth_outbox SET state = 'PENDING', available_at = now() "
                            + "+ (interval '1 second' * ?), lease_until = NULL, last_error = ? "
                            + "WHERE id = ? AND state = 'RUNNING'", (int) backoff, error, id);
        }
        log.warn("auth outbox command {} failed ({}): {}", id, terminal ? "terminal" : "retry", error);
    }

    /** A command that can never be retried (refused, drifted, unknown) goes terminal immediately. */
    public void failTerminal(String id, String lastError) {
        jdbc.update("UPDATE auth_outbox SET state = 'FAILED', lease_until = NULL, last_error = ? "
                + "WHERE id = ? AND state = 'RUNNING'", truncate(lastError), id);
        log.error("auth outbox command {} failed terminally: {}", id, truncate(lastError));
    }

    /** Manual ops recovery for FAILED commands. */
    public void requeue(String id) {
        jdbc.update("UPDATE auth_outbox SET state = 'PENDING', available_at = now(), lease_until = NULL, "
                + "last_error = NULL WHERE id = ? AND state = 'FAILED'", id);
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= 500 ? error : error.substring(0, 497) + "...";
    }
}
