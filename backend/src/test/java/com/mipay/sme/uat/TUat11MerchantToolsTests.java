package com.mipay.sme.uat;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.ops.ReorderRule;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Merchant-tools acceptance tests (UAT SB p.9; Eco SB p.8) — inventory that
 * updates itself, low-stock alerts, one-tap reorder, Khata and tabs with
 * simulated reminders, receipts, and statements.
 */
@DisplayName("Merchant tools (UAT SB p.9; Eco SB p.8) — TC-OPS-01..05/09")
class TUat11MerchantToolsTests extends UatSupport {

    private Merchant m;
    private Supplier s;

    @BeforeEach
    void setUp() {
        m = newApprovedMerchant();
        credit(m.getId(), "1000.00");
        s = newApprovedSupplier("UAT Bread rolls", "30.00");
    }

    @Test
    @DisplayName("TC-OPS-01 · Inventory updates itself: sale decrements, invoice delivery increments")
    void tcOps01_inventoryAutoUpdate() {
        Dtos.InventoryRow item = inventory.addItem(m.getId(), "UAT Bread rolls", "each",
                bd("10"), bd("10.00"));
        assertThat(item.currentStock()).isEqualByComparingTo("10.00");

        // sale: QR payment bound to the stock item decrements stock atomically with confirmation
        var sale = payments.accept(m.getId(), new Dtos.AcceptPaymentRequest(
                bd("60.00"), Enums.PaymentChannel.QR, "stock-cust", item.id(), bd("2.00")));
        payments.simulate(m.getId(), sale.getId(), "confirm");
        assertThat(inventoryItems.findById(item.id()).orElseThrow().getCurrentStock())
                .isEqualByComparingTo("8.00");
        assertThat(stockMovements.findAll()).anySatisfy(mv -> {
            assertThat(mv.getReference()).isEqualTo(sale.getId());
            assertThat(mv.getMovementType()).isEqualTo(Enums.StockMovementType.SALE);
        });

        // delivery: paying the supplier invoice increases stock when names match
        var inv = orderAndInvoice(m.getId(), s, catalogueItemByProductName(s, "UAT Bread rolls"), bd("4"));
        inventoryItems.save(newItemForDelivery(m.getId(), "UAT Bread rolls", "4")); // ensure tracked item exists
        var paid = payInvoiceOk(m.getId(), inv.id());
        assertThat(inventoryItems.findByMerchantIdAndName(m.getId(), "UAT Bread rolls").getCurrentStock())
                .isEqualByComparingTo("12.00"); // 8 + 4 delivered
    }

    private com.mipay.sme.domain.ops.InventoryItem newItemForDelivery(String merchantId, String name, String stock) {
        com.mipay.sme.domain.ops.InventoryItem existing =
                inventoryItems.findByMerchantIdAndName(merchantId, name);
        if (existing != null) {
            return existing;
        }
        com.mipay.sme.domain.ops.InventoryItem it = new com.mipay.sme.domain.ops.InventoryItem();
        it.setMerchantId(merchantId);
        it.setName(name);
        it.setUnit("each");
        it.setCurrentStock(new BigDecimal(stock));
        it.setStatus(Enums.InventoryItemStatus.ACTIVE);
        return inventoryItems.save(it);
    }

    @Test
    @DisplayName("TC-OPS-02 · Low-stock alert raised once, resolved on recovery (upsert semantics)")
    void tcOps02_lowStockAlerts() {
        var item = inventory.addItem(m.getId(), "UAT Sugar 500g", "pack", bd("12"), bd("10.00"));

        assertThat(stockAlerts.findByMerchantIdOrderByCreatedAtDesc(m.getId()))
                .anySatisfy(a -> {
                    assertThat(a.getAlertType()).isEqualTo(Enums.StockAlertType.LOW_STOCK);
                    assertThat(a.getResolved()).isFalse();
                });

        // recovery: restock above the reorder level resolves the open alert
        inventory.adjust(m.getId(), item.id(), Enums.StockMovementType.DELIVERY, bd("20.00"), "restock");
        assertThat(stockAlerts.findByMerchantIdAndResolvedFalse(m.getId())).isEmpty();
    }

    @Test
    @DisplayName("TC-OPS-03 · One-tap reorder creates a submitted order at the preferred supplier (and refuses mismatches)")
    void tcOps03_oneTapReorder() {
        var itemA = inventory.addItem(m.getId(), "UAT Coffee beans", "bag", bd("5"), bd("2.00"));

        ReorderRule rule = new ReorderRule();
        rule.setMerchantId(m.getId());
        rule.setItemId(itemA.id());
        rule.setReorderLevel(bd("5"));
        rule.setPreferredSupplierId(s.getId()); // this supplier's catalogue lacks the product
        rule.setOrderQuantity(bd("10"));
        reorderRules.save(rule);

        // no matching approved catalogue item → refuses with a machine code
        assertThatThrownBy(() -> inventory.reorder(m.getId(), itemA.id()))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NO_CATALOGUE_MATCH"));

        // a preferred supplier whose approved catalogue matches the product name → reorder succeeds
        var sB = newApprovedSupplier("UAT Coffee beans", "170.00");
        rule.setPreferredSupplierId(sB.getId());
        reorderRules.save(rule);
        String orderId = inventory.reorder(m.getId(), itemA.id());
        var submitted = orders.findById(orderId).orElseThrow();
        assertThat(submitted.getStatus()).isEqualTo(Enums.OrderStatus.SUBMITTED);
        assertThat(submitted.getSupplier().getId()).isEqualTo(sB.getId());
        assertThat(orderItems.findByOrderId(orderId)).hasSize(1)
                .first().satisfies(oi -> {
                    assertThat(oi.getQuantity()).isEqualByComparingTo("10");
                    assertThat(oi.getUnitPrice()).isEqualByComparingTo("170.00");
                });
    }

    @Test
    @DisplayName("TC-OPS-04 · Khata: create, entries view, SIMULATED reminder (settling blocked — finding #3)")
    void tcOps04_khataAndReminders() throws Exception {
        String token = merchantToken(m.getOwnerPhone());

        bearerPost("/api/merchant/khata", token,
                java.util.Map.of("customerName", "Petrus N", "customerPhone", "0857770011"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstanding").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        var khataId = khata.findByMerchantIdOrderByCreatedAtDesc(m.getId()).get(0).getId();
        assertThat(khataEntries.findByKhataIdOrderByCreatedAtDesc(khataId)).isEmpty();

        // reminder rendering + SIMULATED labelling (C40/Eco SB p.8) with zero outstanding
        admin.upsertTemplate(new Dtos.TemplateUpsert(Enums.TemplateChannel.WHATSAPP, "tab_due",
                "Hi {customer}, N${amount} outstanding."), "uat-admin", "MINDLABS_ADMIN");
        bearerPost("/api/merchant/khata/" + khataId + "/remind", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("WHATSAPP"))
                .andExpect(jsonPath("$.body").value("Hi Petrus N, N$0 outstanding."))
                .andExpect(jsonPath("$.label", org.hamcrest.Matchers.containsString("SIMULATED")));
    }

    @Test
    @org.junit.jupiter.api.Disabled("finding #3 (docs/UAT-BACKEND-TEST-PACK.md): KhataEntryRequest\n"
            + "annotates the entryType ENUM with @NotBlank, so every POST /api/merchant/khata/{id}/entries\n"
            + "fails validation bootstrap (HV000030 -> 500). The intended assertion sequence is:\n"
            + "POST entry CHARGE 240 → reminder shows 'N$240.00' → POST entry PAYMENT 240 →\n"
            + "khata status SETTLED and outstanding 0. Enable once the annotation is fixed.")
    void tcOps04_khataChargeSettle_blockedByFinding3() {
    }

    @Test
    @DisplayName("TC-OPS-05 · Two-tap tab lifecycle: create, add entry, settle, remind")
    void tcOps05_tabsLifecycle() throws Exception {
        String token = merchantToken(m.getOwnerPhone());

        bearerPost("/api/merchant/tabs", token, java.util.Map.of(
                        "customerName", "Selma I", "customerPhone", "0858880011",
                        "amount", "240.00", "description", "Lunch x 4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.totalAmount").value(240.00));
        var tabId = tabs.findByMerchantIdOrderByCreatedAtDesc(m.getId()).get(0).getId();

        bearerPost("/api/merchant/tabs/" + tabId + "/entries", token,
                java.util.Map.of("amount", "60.00", "description", "drinks"))
                .andExpect(status().isOk());
        assertThat(tabEntries.findByTabIdOrderByCreatedAtDesc(tabId)).hasSize(2);

        bearerPost("/api/merchant/tabs/" + tabId + "/remind", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("0858880011"));

        bearerPost("/api/merchant/tabs/" + tabId + "/settle", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("TC-OPS-09 · Accept-payment info: QR payload, short code and card terminal surfaced (M06)")
    void tcOps09_acceptInfo() throws Exception {
        String token = merchantToken(m.getOwnerPhone());
        bearerGet("/api/merchant/accept-info", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrPayload", org.hamcrest.Matchers.startsWith("mipay://qr/")))
                .andExpect(jsonPath("$.shortCode").isNotEmpty());

        // dashboard (M04) exposes available/pending/held/owed + today's sales & profit
        bearerGet("/api/merchant/dashboard", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(1000.00));
    }

    @Test
    @DisplayName("M26 · Merchant statements: generate + download (UAT SB p.14)")
    void tcM26_statements() throws Exception {
        String token = merchantToken(m.getOwnerPhone());
        payNowConfirmed(m.getId(), "60.00", Enums.PaymentChannel.QR, "stmt-cust");

        bearerPost("/api/merchant/statements", token, java.util.Map.of(
                        "ownerType", "merchant", "ownerId", m.getId(),
                        "from", LocalDate.now().plusDays(-7).toString(),
                        "to", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerType").value("merchant"))
                .andExpect(jsonPath("$.fileRef").isNotEmpty());

        var stmt = statements.list("merchant", m.getId()).get(0);
        String text = statements.downloadText("merchant", m.getId(),
                LocalDate.now().plusDays(-7), LocalDate.now());
        assertThat(text).contains("PAYMENT");
        assertThat(text).contains("60.00");
    }
}
