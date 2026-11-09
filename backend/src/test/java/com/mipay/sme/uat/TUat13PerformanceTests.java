package com.mipay.sme.uat;

import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UAT SB p.17 / BC p.18 service targets.
 *
 * TC-PERF-01 runs a sustained 20 TPS load against the real backend:
 * - default: a 30-second smoke (≈600 transactions) always runs with the suite;
 * - full acceptance (30 minutes at 20 TPS) is enabled with
 *   `-Duat.long=true` (or env UAT_LONG=1) — see docs/UAT-BACKEND-TEST-PACK.md.
 */
@DisplayName("Performance & service targets (UAT SB p.17) — TC-PERF")
class TUat13PerformanceTests extends UatSupport {

    private static final int TPS = 20;

    private boolean longRun() {
        String prop = System.getProperty("uat.long");
        String env = System.getenv("UAT_LONG");
        return Boolean.parseBoolean(prop) || (env != null && !"false".equalsIgnoreCase(env));
    }

    private int durationSeconds() {
        String p = System.getProperty("uat.load.seconds");
        if (p != null) {
            return Math.max(5, Integer.parseInt(p));
        }
        return longRun() ? 1800 : 30; // full acceptance: 30 minutes
    }

    @Test
    @DisplayName("TC-PERF-01 · 20 TPS sustained: zero failed commits, no ledger drift")
    void tcPerf01_sustainedLoad() throws Exception {
        int seconds = durationSeconds();
        final int totalOps = TPS * seconds;

        var m = newApprovedMerchant();
        final AtomicInteger scheduled = new AtomicInteger(0);
        final AtomicInteger success = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(totalOps);
        final long startNanos = System.nanoTime();

        for (int w = 0; w < 4; w++) {
            pool.submit(() -> {
                while (true) {
                    int index = scheduled.incrementAndGet();
                    if (index > totalOps) {
                        return;
                    }
                    long targetNanos = startNanos + (long) index * 1_000_000_000L / TPS;
                    while (System.nanoTime() < targetNanos) { /* pace to TPS */ }
                    try {
                        var p = payments.accept(m.getId(), new Dtos.AcceptPaymentRequest(
                                new BigDecimal("10.00"), com.mipay.sme.domain.Enums.PaymentChannel.QR,
                                "loader-" + index, null, null));
                        com.mipay.sme.web.Dtos.PaymentOpResult r =
                                payments.simulate(m.getId(), p.getId(), "confirm");
                        if (!(r instanceof Dtos.PaymentConfirmed)) {
                            throw new IllegalStateException("not confirmed: " + r);
                        }
                        if (success.incrementAndGet() == totalOps) {
                            return;
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        errors.add(index + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                }
            });
        }
        done.await((long) (seconds + 600), TimeUnit.SECONDS);
        pool.shutdownNow();
        long elapsedNanos = System.nanoTime() - startNanos;
        double durationSec = elapsedNanos / 1_000_000_000.0;
        double achievedTps = success.get() / durationSec;

        assertThat(errors).as("no failed commits during sustained load").isEmpty();
        assertThat(success.get()).as("all scheduled operations completed").isEqualTo(totalOps);
        assertThat(achievedTps).as("achieved TPS must be ≥ 19.5 (target 20)").isGreaterThanOrEqualTo(19.5);

        // no ledger drift: issuer ledger == mirrored subledger == expected sales sum
        var issuerNet = ledger.issuerNetPerOwner().get(m.getId());
        var subNet = ledger.confirmedNetPerOwner().get(m.getId());
        BigDecimal expected = new BigDecimal("10.00").multiply(BigDecimal.valueOf(totalOps));
        assertThat(issuerNet).isEqualByComparingTo(expected);
        assertThat(subNet).isEqualByComparingTo(expected);

        // reconciliation closes balanced after the load
        var run = recon.run();
        assertThat(statusOf(run)).isEqualTo("MATCHED");
    }

    @Test
    @DisplayName("TC-PERF-02 · Platform health reports ≥ 99.9% availability and live latency/TPS")
    void tcPerf02_availabilityTarget() throws Exception {
        ops.touchMetrics();
        Dtos.HealthView health = reports.health();
        assertThat(health.availabilityPct()).isGreaterThanOrEqualTo(new BigDecimal("99.9"));
        assertThat(health.p95LatencyMs()).isPositive();
        assertThat(health.environment()).isEqualTo("sandbox");
        assertThat(openCritical(health)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("TC-PERF-03 · Incident SLA plumbing available for service targets (15-min ack)")
    void tcPerf03_incidentAckPathFasterThanSla() throws Exception {
        long start = System.nanoTime();
        Dtos.IncidentRow incident = ops.createIncident(new Dtos.CreateIncidentRequest(
                "UAT perf incident " + seq(), com.mipay.sme.domain.Enums.IncidentSeverity.CRITICAL,
                "acknowledgement-path smoke"), "uat-ops");
        Dtos.IncidentRow acked = ops.acknowledge(incident.id(), "uat-ops");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(acked.acknowledgedLate()).as("critical ack inside the 15-minute SLA").isFalse();
        assertThat(millis).as("ack path must complete well inside the SLA").isLessThan(15_000);
    }

    private int openCritical(Dtos.HealthView h) {
        return h.openCriticalIncidents();
    }
}
