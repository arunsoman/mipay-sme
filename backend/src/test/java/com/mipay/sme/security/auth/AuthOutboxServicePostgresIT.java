package com.mipay.sme.security.auth;

import com.mipay.sme.domain.core.AuthOutbox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres integration test for the durable provider-command outbox: ON CONFLICT dedup,
 * FOR UPDATE SKIP LOCKED leasing (disjoint across concurrent workers), retry backoff, terminal
 * marking, expired-lease recovery and requeue — the leasing guarantees the goal's failure-window
 * requirements rest on. Runs against the test Postgres (xrbac-test-pg / sme-db) in a throwaway
 * database; skipped on machines without Postgres (documented; acceptance runs execute it).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthOutboxServicePostgresIT {
    private static String dbName;
    private static JdbcTemplate jdbc;
    private static boolean available;

    @BeforeAll
    static void provision() throws Exception {
        available = ping();
        Assumptions.assumeTrue(available, "no Postgres reachable for the real-infra outbox test");
        dbName = "auth_outbox_it_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = connection("postgres"); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        }
        JdbcTemplate fresh = new JdbcTemplate(new org.springframework.jdbc.datasource
                .DriverManagerDataSource(url(dbName), "t", "t"));
        // Exactly the V15 DDL for the outbox (see V15__auth_identity_foundation.sql).
        fresh.execute("""
                CREATE TABLE auth_outbox (
                    id varchar(36) PRIMARY KEY,
                    user_id varchar(36) NOT NULL,
                    provider varchar(64) NOT NULL,
                    command varchar(64) NOT NULL,
                    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
                    state varchar(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (state IN ('PENDING','RUNNING','DONE','FAILED')),
                    attempts integer NOT NULL DEFAULT 0,
                    available_at timestamptz NOT NULL DEFAULT now(),
                    lease_until timestamptz,
                    last_error varchar(500),
                    created_at timestamptz NOT NULL DEFAULT now(),
                    completed_at timestamptz,
                    dedupe_key varchar(255) UNIQUE
                )""");
        fresh.execute("CREATE INDEX ix_auth_outbox_ready ON auth_outbox(state, available_at)");
        jdbc = fresh;
    }

    @AfterAll
    static void teardown() throws Exception {
        if (available && dbName != null) try (Connection c = connection("postgres"); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE " + dbName);
        } catch (Exception ignored) { }
    }

    private static boolean ping() {
        try (Connection ignored = connection("postgres")) { return true; } catch (Exception e) { return false; }
    }

    private static Connection connection(String db) throws Exception {
        return DriverManager.getConnection(url(db), "t", "t");
    }

    private static String url(String db) {
        return "jdbc:postgresql://127.0.0.1:25433/" + db;
    }

    private AuthOutboxService service() {
        return new AuthOutboxService(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    @Order(1)
    void enqueueLeaseCompleteCycle() {
        AuthOutboxService.EnqueueResult a = service().enqueue("u-cycle", "trustplane", "ENROL",
                Map.of("user_id", "u-cycle", "login_name", "cycle"), "enrol:u-cycle");
        assertFalse(a.duplicate());
        List<AuthOutbox> leased = service().leaseBatch(10, java.time.Duration.ofSeconds(60));
        assertEquals(1, leased.size());
        assertEquals("u-cycle", leased.get(0).getUserId());
        assertEquals(Map.of("user_id", "u-cycle", "login_name", "cycle"), leased.get(0).getPayload());
        service().complete(leased.get(0).getId());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM auth_outbox WHERE state='DONE'", Long.class));
    }

    @Test
    @Order(2)
    void duplicateDedupeKeyReturnsExistingRowWithoutSecondInsert() {
        AuthOutboxService s = service();
        AuthOutboxService.EnqueueResult first = s.enqueue("u-dup", "trustplane", "RESET_LINK",
                Map.of("user_id", "u-dup", "subject", "s1"), "reset:u-dup:1");
        AuthOutboxService.EnqueueResult again = s.enqueue("u-dup", "trustplane", "RESET_LINK",
                Map.of("user_id", "u-dup", "subject", "s1"), "reset:u-dup:1");
        assertTrue(again.duplicate());
        assertEquals(first.id(), again.id());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM auth_outbox WHERE dedupe_key='reset:u-dup:1'", Long.class));
    }

    @Test
    @Order(3)
    void concurrentLeasesAreDisjointAcrossWorkers() throws Exception {
        for (int i = 0; i < 40; i++)
            service().enqueue("u-race-" + i, "trustplane", "DISABLE", Map.of("subject", "s-" + i), null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int w = 0; w < 8; w++)
                futures.add(pool.submit((Callable<List<String>>) () -> {
                    List<String> ids = new ArrayList<>();
                    for (AuthOutbox row : service().leaseBatch(5, java.time.Duration.ofSeconds(60)))
                        ids.add(row.getId());
                    Thread.sleep(50); // hold the lease a moment, like a real provider call
                    return ids;
                }));
            Set<String> seen = Collections.synchronizedSet(new java.util.HashSet<>());
            int total = 0;
            for (Future<List<String>> f : futures) {
                for (String id : f.get()) {
                    assertTrue(seen.add(id), "row leased twice: " + id);
                    total++;
                }
            }
            assertEquals(40, total);
        } finally {
            pool.shutdownNow();
        }
        // hygiene: don't leave 40 stale RUNNING leases to flood later tests' reclaim batches
        jdbc.update("UPDATE auth_outbox SET state = 'DONE', completed_at = now() WHERE state = 'RUNNING'");
    }

    @Test
    @Order(4)
    void failuresBackOffUntilTerminal() {
        AuthOutboxService s = service();
        AuthOutboxService.EnqueueResult r = s.enqueue("u-retry", "trustplane", "ENABLE",
                Map.of("subject", "s-retry"), null);
        AuthOutbox leased = s.leaseBatch(10, java.time.Duration.ofSeconds(60)).stream()
                .filter(row -> "u-retry".equals(row.getUserId())).findFirst().orElseThrow();
        s.fail(leased.getId(), "IdP down");
        Instant backoff = jdbc.queryForObject("SELECT available_at FROM auth_outbox WHERE id=?",
                Instant.class, r.id());
        assertTrue(backoff != null && backoff.isAfter(Instant.now().plusSeconds(2)), "first failure should back off");
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM auth_outbox WHERE id=?", String.class, r.id()));

        // burn attempts: force availability forward, lease, fail, repeat
        for (int n = 2; n <= AuthOutboxService.MAX_ATTEMPTS; n++) {
            jdbc.execute("UPDATE auth_outbox SET available_at = now()");
            List<AuthOutbox> again = s.leaseBatch(10, java.time.Duration.ofSeconds(60)).stream()
                    .filter(row -> "u-retry".equals(row.getUserId())).toList();
            assertEquals(1, again.size());
            s.fail(again.get(0).getId(), "still down");
        }
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM auth_outbox WHERE id=?", String.class, r.id()));
        assertEquals(AuthOutboxService.MAX_ATTEMPTS,
                jdbc.queryForObject("SELECT attempts FROM auth_outbox WHERE id=?", Integer.class, r.id()));
    }

    @Test
    @Order(5)
    void terminalFailuresAndLongErrorsAreRecordedThenRequeued() {
        AuthOutboxService s = service();
        AuthOutboxService.EnqueueResult r = s.enqueue("u-term", "trustplane", "DELETE", Map.of("subject", "s-term"), null);
        s.leaseBatch(10, java.time.Duration.ofSeconds(60));
        s.failTerminal(r.id(), "x".repeat(900));
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM auth_outbox WHERE id=?", String.class, r.id()));
        assertEquals(500, jdbc.queryForObject("SELECT length(last_error) FROM auth_outbox WHERE id=?", Integer.class, r.id()));

        s.requeue(r.id());
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM auth_outbox WHERE id=?", String.class, r.id()));
    }

    @Test
    @Order(6)
    void expiredLeasesAreReclaimedAfterWorkerCrash() {
        AuthOutboxService s = service();
        AuthOutboxService.EnqueueResult r = s.enqueue("u-crash", "trustplane", "ENABLE", Map.of("subject", "s-crash"), null);
        List<AuthOutbox> leased = s.leaseBatch(10, java.time.Duration.ofSeconds(60));
        assertTrue(leased.stream().anyMatch(row -> r.id().equals(row.getId())), "own row leased");
        // simulate a crashed worker: its RUNNING lease lapses without completion
        jdbc.execute("UPDATE auth_outbox SET lease_until = now() - interval '1 minute'");
        List<AuthOutbox> reclaimed = s.leaseBatch(60, java.time.Duration.ofSeconds(60));
        AuthOutbox reclaimedRow = reclaimed.stream().filter(row -> r.id().equals(row.getId())).findFirst().orElseThrow();
        assertEquals(2, reclaimedRow.getAttempts());
        s.complete(reclaimedRow.getId());
        assertEquals("DONE", jdbc.queryForObject("SELECT state FROM auth_outbox WHERE id=?", String.class, r.id()));
    }
}
