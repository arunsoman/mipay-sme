package com.mipay.sme.service;

import com.mipay.sme.common.SimException;
import com.mipay.sme.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bank / issuer simulator (sandbox).
 *
 * Every scheduled callback runs on its own <b>virtual thread</b>
 * ({@link Executors#newVirtualThreadPerTaskExecutor()}); sleeping threads cost
 * nothing, which is exactly the point of virtual threads for hundreds of
 * in-flight simulated bank callbacks (UAT SB p.16, BC p.17).
 *
 * Scenarios drive the UAT fault paths - the single-effect guarantee is
 * enforced downstream by the payment/invoice callback dedup keys, so a
 * DUPLICATE replay always moves money at most once (TC-ACC-04, TC-B2B-03):
 *
 * | Scenario    | Behaviour                                                     |
 * |-------------|---------------------------------------------------------------|
 * | MANUAL      | nobody auto-fires; the ops desk / UI buttons drive outcomes   |
 * | CONFIRM     | single confirmation after ~1.2 s                              |
 * | DUPLICATE   | confirm, then a replayed callback that must be absorbed       |
 * | TIMEOUT     | timeout window first, payment stays pending, then late confirm|
 * | LATE_CONFIRM| only a late final answer                                      |
 * | FAIL        | failure callback                                              |
 *
 * A scenario can be forced per payment initiation with the {@code X-Sim-Scenario}
 * header, or set platform-wide from Mindlabs admin (/api/admin/sim/controls).
 * The platform default is MANUAL, so existing flows are unchanged unless ops
 * turns the simulator on. Disabled - like everything simulated - in production.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankSimulator {

    public enum Scenario { MANUAL, CONFIRM, DUPLICATE, TIMEOUT, LATE_CONFIRM, FAIL }

    private final ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor();
    private final AppProperties props;
    private final PaymentService paymentService;
    private final SimulationService simulationService;
    private final OpsService opsService;
    private final AuditService auditService;
    private volatile Scenario defaultScenario = Scenario.MANUAL;
    private final AtomicLong bankRefSeq = new AtomicLong(1000);

    public Scenario defaultScenario() {
        return defaultScenario;
    }

    public String nextBankReference() {
        return "SIM-BT-" + bankRefSeq.incrementAndGet();
    }

    /** Platform-wide default; audited as a simulated ops action. */
    public void setDefaultScenario(Scenario scenario, String actorId, String actorRole) {
        this.defaultScenario = scenario;
        auditService.record(actorId, actorRole, "bank_sim_scenario_changed", "simulator", "platform",
                null, null, "scenario=" + scenario + " (SIMULATED bank controls, UAT SB p.16)", true, null);
        log.info("Bank simulator default scenario set to {} by {}", scenario, actorId);
    }

    public Scenario parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Scenario.valueOf(header.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new SimException("SIM_SCENARIO",
                    "Unknown X-Sim-Scenario: " + header + " (use CONFIRM | DUPLICATE | TIMEOUT | LATE_CONFIRM | FAIL)");
        }
    }

    /** Whether a customer-payment callback flow should start automatically. */
    public boolean shouldAutoRun(Scenario requested) {
        Scenario s = requested == null ? defaultScenario : requested;
        return s != Scenario.MANUAL;
    }

    // ------------------------------------------------------------------ customer receipts

    public void scheduleCustomerPayment(String merchantId, String paymentId, Scenario scenario) {
        Scenario s = scenario == null ? defaultScenario : scenario;
        if (s == Scenario.MANUAL) {
            return;
        }
        vthreads.submit(() -> runCustomerFlow(merchantId, paymentId, s));
    }

    private void runCustomerFlow(String merchantId, String paymentId, Scenario s) {
        try {
            switch (s) {
                case CONFIRM -> {
                    sleep(props.getSim().getConfirmDelayMs());
                    fire(merchantId, paymentId, "confirm");
                }
                case DUPLICATE -> {
                    sleep(props.getSim().getConfirmDelayMs());
                    fire(merchantId, paymentId, "confirm");
                    sleep(props.getSim().getDuplicateDelayMs());
                    fire(merchantId, paymentId, "confirm"); // replay expected to be absorbed
                }
                case TIMEOUT -> {
                    sleep(props.getSim().getTimeoutWindowMs());
                    fire(merchantId, paymentId, "timeout");
                    sleep(props.getSim().getLateConfirmDelayMs());
                    fire(merchantId, paymentId, "confirm"); // late final answer
                }
                case LATE_CONFIRM -> {
                    sleep(props.getSim().getTimeoutWindowMs() + props.getSim().getLateConfirmDelayMs());
                    fire(merchantId, paymentId, "confirm");
                }
                case FAIL -> {
                    sleep(props.getSim().getConfirmDelayMs());
                    fire(merchantId, paymentId, "fail");
                }
                default -> { /* MANUAL never scheduled */ }
            }
        } catch (Exception e) {
            log.error("Simulated bank flow failed for payment {}: {}", paymentId, e.getMessage(), e);
        }
    }

    private void fire(String merchantId, String paymentId, String action) {
        try {
            paymentService.simulate(merchantId, paymentId, action);
            if ("timeout".equals(action) || "fail".equals(action)) {
                opsService.raiseSystem("HIGH", "Simulated bank " + action,
                        "Bank simulator drove a " + action + " for payment " + paymentId
                                + ". Payment stays pending / funds unmoved until the final answer.",
                        paymentId);
            }
        } catch (Exception e) {
            log.warn("Simulated {} callback for payment {} rejected: {}", action, paymentId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ B2B supplier payments

    /** Late final answer for a timed-out supplier payment (TC-B2B-04). */
    public void scheduleInvoiceOutcome(String invoicePaymentId, Scenario scenario) {
        Scenario s = scenario == null ? defaultScenario : scenario;
        if (s == Scenario.MANUAL) {
            return;
        }
        vthreads.submit(() -> runInvoiceFlow(invoicePaymentId, s));
    }

    private void runInvoiceFlow(String invoicePaymentId, Scenario s) {
        try {
            switch (s) {
                case CONFIRM -> {
                    sleep(props.getSim().getConfirmDelayMs());
                    fireInvoice(invoicePaymentId, "confirm");
                }
                case DUPLICATE -> {
                    sleep(props.getSim().getConfirmDelayMs());
                    fireInvoice(invoicePaymentId, "confirm");
                    sleep(props.getSim().getDuplicateDelayMs());
                    fireInvoice(invoicePaymentId, "confirm"); // must not double-debit
                }
                case TIMEOUT -> {
                    sleep(props.getSim().getTimeoutWindowMs());
                    fireInvoice(invoicePaymentId, "timeout");
                    sleep(props.getSim().getLateConfirmDelayMs());
                    fireInvoice(invoicePaymentId, "confirm"); // hold converts, invoice paid
                }
                case LATE_CONFIRM -> {
                    sleep(props.getSim().getTimeoutWindowMs() + props.getSim().getLateConfirmDelayMs());
                    fireInvoice(invoicePaymentId, "confirm");
                }
                case FAIL -> {
                    sleep(props.getSim().getConfirmDelayMs());
                    fireInvoice(invoicePaymentId, "fail");
                }
                default -> { }
            }
        } catch (Exception e) {
            log.error("Simulated invoice flow failed for {}: {}", invoicePaymentId, e.getMessage(), e);
        }
    }

    private void fireInvoice(String invoicePaymentId, String outcome) {
        try {
            simulationService.simulateInvoicePayment(invoicePaymentId, outcome, "system", "MACHINE");
        } catch (Exception e) {
            log.warn("Simulated {} outcome for invoice payment {} rejected: {}",
                    outcome, invoicePaymentId, e.getMessage());
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
