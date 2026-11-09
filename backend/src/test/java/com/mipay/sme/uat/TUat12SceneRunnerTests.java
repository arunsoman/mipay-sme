package com.mipay.sme.uat;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.core.AuditLog;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Simulation & environment controls (E30-E34, UAT SB p.16, BC p.17):
 * the scene runner must reproduce all eight demonstration scenes, every
 * simulated event must be labelled, and simulation must be impossible in
 * production.
 */
@DisplayName("Simulation & scene runner (UAT SB p.16; BC p.17) — TC-SET-28..31")
class TUat12SceneRunnerTests extends UatSupport {

    @BeforeEach
    @AfterEach
    void reconHygiene() {
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-SET-28b · Scenario catalogue lists all eight labelled scenes (E33)")
    void tcScenarios_listedAndLabelled() {
        List<Dtos.ScenarioRow> rows = simulation.scenarios();
        assertThat(rows).hasSize(8);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.isLabelled()).isTrue();  // every simulation is labelled (BC p.17)
            assertThat(r.enabled()).isTrue();
        });
        assertThat(rows).extracting(Dtos.ScenarioRow::name)
                .containsExactlyInAnyOrder("Scene 1", "Scene 2", "Scene 3", "Scene 4",
                        "Scene 5", "Scene 6", "Scene 7", "Scene 8");
    }

    @Test
    @DisplayName("Scene 1 · Onboard + first QR payment confirmed once")
    void tcScene1_firstQrPayment() {
        var result = simulation.runScene("Scene 1", "uat-admin", "MINDLABS_ADMIN");
        assertThat(result.steps()).isNotEmpty();
        assertThat(result.steps()).allSatisfy(s -> assertThat(s.ok()).isTrue());
        assertThat(result.steps()).anySatisfy(s -> assertThat(s.detail()).contains("N$60"));
    }

    @Test
    @DisplayName("Scene 2 · Duplicate callback ignored (single-effect guarantee)")
    void tcScene2_duplicateIgnored() {
        var result = simulation.runScene("Scene 2", "uat-admin", "MINDLABS_ADMIN");
        assertThat(result.steps()).allSatisfy(s -> assertThat(s.ok()).isTrue());
        assertThat(result.steps()).anySatisfy(s -> assertThat(s.detail()).containsIgnoringCase("ignored"));
    }

    @Test
    @DisplayName("Scene 3 · Pay supplier invoice atomically")
    void tcScene3_payInvoiceAtomically() {
        var seedM = seedMerchant();
        var seedS = seedSupplier();
        String item = catalogueItemByProductName(seedS, "USB-C cable 1m");
        var inv = orderAndInvoice(seedM.getId(), seedS, item, java.math.BigDecimal.ONE); // N$45 payable

        var result = simulation.runScene("Scene 3", "uat-admin", "MINDLABS_ADMIN");
        assertThat(result.steps()).allSatisfy(s -> assertThat(s.ok()).isTrue());
        assertThat(invoiceEntity(inv.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("Scene 4 · Card + timeout: pending preserved")
    void tcScene4_cardTimeoutPreserved() {
        var result = simulation.runScene("Scene 4", "uat-admin", "MINDLABS_ADMIN");
        assertThat(result.steps()).allSatisfy(s -> assertThat(s.ok()).isTrue());
        assertThat(result.steps()).anySatisfy(s -> assertThat(s.detail()).contains("PENDING"));
    }

    @Test
    @DisplayName("Scene 5 · Oversized supplier payment is refused deterministically")
    void tcScene5_insufficientDemo() {
        var seedM = seedMerchant();
        var seedS = seedSupplier();
        String item = catalogueItemByProductName(seedS, "Oukitel 20,000mAh power bank");
        orderAndInvoice(seedM.getId(), seedS, item, java.math.BigDecimal.valueOf(2000)); // N$680,000

        var result = simulation.runScene("Scene 5", "uat-admin", "MINDLABS_ADMIN");
        assertThat(result.steps()).allSatisfy(s -> assertThat(s.ok()).isTrue());
    }

    @Test
    @DisplayName("Scene 6 · Consent → assessment → offer → fund → sweep completes on the seed merchant")
    void tcScene6_advanceFundedAndSwept() {
        var result = simulation.runScene("Scene 6", "uat-admin", "MINDLABS_ADMIN");
        assertThat(result.steps()).isNotEmpty();
        assertThat(result.steps()).anySatisfy(s -> {
            assertThat(s.ok()).isTrue();
            assertThat(s.detail()).contains("decision=");
        });
        var seedM = seedMerchant();
        var view = advances.currentAdvance(seedM.getId());
        assertThat(view.advance()).isNotNull();
        assertThat(view.advance().status())
                .isIn(Enums.AdvanceStatus.FUNDED, Enums.AdvanceStatus.REPAYING, Enums.AdvanceStatus.CLOSED);
    }

    @Test
    @DisplayName("Scene 7 · Recon break injected → run flags it → withdrawal release is blocked")
    void tcScene7_reconBreakBlocksRelease() {
        var result = simulation.runScene("Scene 7", "uat-admin", "MINDLABS_ADMIN");
        assertThat(result.steps()).allSatisfy(s -> assertThat(s.ok()).isTrue());

        // independently verify: withdrawal is refused while breaks are open
        var seedM = seedMerchant();
        assertThatThrownBy(() -> withdrawals.withdraw(seedM.getId(), java.math.BigDecimal.ONE,
                "uat-ops", "TPTS_OPS"))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RECON_BLOCKED"));
    }

    @Test
    @DisplayName("Scene 8 · Refund + suspension cycle reproduce")
    void tcScene8_refundAndSuspension() {
        payNowConfirmed(seedMerchant().getId(), "40.00", Enums.PaymentChannel.QR, "scene8-cust");
        var result = simulation.runScene("Scene 8", "uat-admin", "MINDLABS_ADMIN");
        assertThat(result.steps()).allSatisfy(s -> assertThat(s.ok()).isTrue());
        assertThat(result.steps()).anySatisfy(s -> assertThat(s.detail()).contains("Refunded N$10.00"));
    }

    @Test
    @DisplayName("TC-SET-28 · All simulation-layer audit entries are labelled simulated=true (E34)")
    void tcAllSimulatedEventsLabelled() {
        // drive the labelled scene ourselves so the assertion is order-independent
        // (per-class DB isolation removed cross-class audit carry-over - the expected
        // labelled actions must be produced by this test's own fixture)
        simulation.runScene("Scene 2", "uat-admin", "MINDLABS_ADMIN"); // duplicate callback absorbed
        List<AuditLog> page = auditRepo.search(true, PageRequest.of(0, 200)).getContent();
        assertThat(page).isNotEmpty();
        assertThat(page).allSatisfy(a -> assertThat(a.getSimulated()).isTrue());
        // key simulated actions present in the labelled audit trail
        assertThat(page).extracting(AuditLog::getAction)
                .contains("scene_run", "synthetic_history_generated",
                        "duplicate_callback_ignored", "payment_confirmed");
    }

    @Test
    @DisplayName("E30 · Simulation layer is disabled when app.environment = production")
    void tcSimulationDisabledInProduction() {
        String env = appProperties.getEnvironment();
        try {
            appProperties.setEnvironment("production");
            assertThatThrownBy(() -> simulation.runScene("Scene 1", "uat-admin", "MINDLABS_ADMIN"))
                    .isInstanceOf(com.mipay.sme.common.ForbiddenException.class)
                    .hasMessageContaining("disabled in production");
        } finally {
            appProperties.setEnvironment(env); // restore sandbox context
        }
    }
}
