package com.mipay.sme.web;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.AuditLog;
import com.mipay.sme.domain.core.DeviceSession;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.ops.KhataAccount;
import com.mipay.sme.domain.ops.KhataEntry;
import com.mipay.sme.domain.ops.Tab;
import com.mipay.sme.domain.ops.TabEntry;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.KhataAccountRepository;
import com.mipay.sme.repository.KhataEntryRepository;
import com.mipay.sme.repository.NotificationTemplateRepository;
import com.mipay.sme.repository.TabEntryRepository;
import com.mipay.sme.repository.TabRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.AdvanceService;
import com.mipay.sme.service.AuditService;
import com.mipay.sme.service.B2BPaymentService;
import com.mipay.sme.service.FeeService;
import com.mipay.sme.service.InventoryService;
import com.mipay.sme.service.LedgerService;
import com.mipay.sme.service.MerchantViewsService;
import com.mipay.sme.service.PaymentService;
import com.mipay.sme.service.StatementService;
import com.mipay.sme.service.WithdrawalService;
import com.mipay.sme.web.Dtos;
import com.mipay.sme.repository.InvoiceRepository;
import com.mipay.sme.repository.PaymentEventRepository;
import com.mipay.sme.repository.PaymentRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.mipay.sme.repository.MerchantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant mobile app API (M01-M29). Every endpoint scopes to the JWT's
 * merchant identity; path params are never trusted for tenancy (TC-SEC-01).
 */
@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final LedgerService ledgerService;
    private final PaymentService paymentService;
    private final B2BPaymentService b2bService;
    private final com.mipay.sme.service.BankSimulator bankSimulator;
    private final com.mipay.sme.config.AppProperties props;
    private final AdvanceService advanceService;
    private final FeeService feeService;
    private final WithdrawalService withdrawalService;
    private final InventoryService inventoryService;
    private final StatementService statementService;
    private final MerchantViewsService viewsService;
    private final AuditService auditService;

    private final MerchantRepository merchantRepo;
    private final InvoiceRepository invoiceRepo;
    private final PaymentRepository paymentRepo;
    private final PaymentEventRepository eventRepo;
    private final KhataAccountRepository khataRepo;
    private final KhataEntryRepository khataEntryRepo;
    private final TabRepository tabRepo;
    private final TabEntryRepository tabEntryRepo;
    private final NotificationTemplateRepository templateRepo;
    private final DeviceSessionRepository sessionRepo;
    private final com.mipay.sme.repository.CreditConsentRepository consentRepo;
    private final com.mipay.sme.repository.StockAlertRepository stockAlertRepo;
    private final com.mipay.sme.repository.ReorderRuleRepository reorderRuleRepo;
    private final com.mipay.sme.repository.InventoryItemRepository inventoryItemRepo;
    private final com.mipay.sme.repository.SupplierRepository supplierRepo;
    private final com.mipay.sme.repository.SupplierCatalogueItemRepository catalogItemRepo;
    private final com.mipay.sme.repository.OrderRepository orderRepo;
    private final com.mipay.sme.repository.AdvanceRepository advanceRepo;

    private String mid() {
        return Current.merchantId();
    }

    private String actorId() {
        return Current.get().userId();
    }

    private String actorRole() {
        return Current.get().role();
    }

    // ------------------------------------------------------------------ dashboard / balance

    @GetMapping("/dashboard")
    public Dtos.DashboardView dashboard() {
        return viewsService.dashboard(mid());
    }

    @GetMapping("/balance")
    public Dtos.BalanceView balance() {
        return ledgerService.balanceView(mid());
    }

    @GetMapping("/balance/entries")
    public List<Dtos.LedgerRow> ledger(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "100") int size) {
        return viewsService.ledgerPage(mid(), page, size);
    }

    @PostMapping("/withdrawals")
    public Dtos.WithdrawalRow withdraw(@Valid @RequestBody Dtos.WithdrawalRequest req) {
        com.mipay.sme.security.MoneyGuard.requireAdmin(com.mipay.sme.security.Current.get());
        return withdrawalService.withdraw(mid(), req.amount(), actorId(), actorRole());
    }

    @GetMapping("/withdrawals")
    public List<Dtos.WithdrawalRow> withdrawals() {
        return withdrawalService.list(mid());
    }

    @GetMapping("/billing-preview")
    public Dtos.FeePreview billingPreview(@RequestParam(defaultValue = "60") BigDecimal amount) {
        return feeService.preview(amount);
    }

    // ------------------------------------------------------------------ accept payment (M06-M12)

    @PostMapping("/payments")
    public Dtos.PaymentPending accept(@Valid @RequestBody Dtos.AcceptPaymentRequest req,
                                      @RequestHeader(value = "X-Sim-Scenario", required = false) String scenarioHeader) {
        com.mipay.sme.security.MoneyGuard.requireAdmin(com.mipay.sme.security.Current.get());
        var p = paymentService.accept(mid(), req);
        // Async bank-simulator flows (UAT SB p.16): per-request X-Sim-Scenario or the
        // platform default set by ops. MANUAL default keeps the existing manual flow.
        var scenario = bankSimulator.parse(scenarioHeader);
        if (props.isSimulatedEnvironment() && bankSimulator.shouldAutoRun(scenario)) {
            bankSimulator.scheduleCustomerPayment(mid(), p.getId(),
                    scenario == null ? bankSimulator.defaultScenario() : scenario);
        }
        return new Dtos.PaymentPending(p.getId(), p.getAmount(), "Awaiting customer payment");
    }

    @GetMapping("/payments")
    public List<Dtos.PaymentRow> payments(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return paymentService.history(mid(), page, size);
    }

    @GetMapping("/payments/{id}")
    public Dtos.PaymentDetailRow paymentDetail(@PathVariable String id) {
        com.mipay.sme.domain.payments.Payment p = paymentRepo.findDetailWithMerchant(id, mid())
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        List<Dtos.EventRow> events = eventRepo.findByPaymentIdOrderByCreatedAtAsc(p.getId()).stream()
                .map(e -> new Dtos.EventRow(e.getEventType().name(), e.getPayloadHash(), e.getMeta(), e.getCreatedAt()))
                .toList();
        return new Dtos.PaymentDetailRow(p.getId(), p.getMerchant().getBusinessName(),
                p.getChannel() == null ? null : p.getChannel().name(), p.getAmount(), p.getStatus().name(),
                p.getPayerRef(), p.getCreatedAt(), p.getConfirmedAt(), events);
    }

    @PostMapping("/payments/{id}/simulate")
    public Dtos.PaymentOpResult simulate(@PathVariable String id, @Valid @RequestBody Dtos.SimTrigger req) {
        return paymentService.simulate(mid(), id, req.action());
    }

    @PostMapping("/payments/{id}/refund")
    public Dtos.RefundRow refund(@PathVariable String id, @Valid @RequestBody Dtos.RefundRequest req) {
        com.mipay.sme.security.MoneyGuard.requireAdmin(com.mipay.sme.security.Current.get());
        return paymentService.refund(mid(), id, req);
    }

    @GetMapping("/speaker-log")
    public List<Dtos.SpeakerLogRow> speakerLog() {
        return paymentService.speakerLog(mid());
    }

    @GetMapping("/accept-info")
    public Dtos.PayInfo payInfo() {
        return viewsService.payInfo(mid());
    }

    // ------------------------------------------------------------------ invoices / pay supplier (M13-M19)

    @GetMapping("/invoices")
    public List<Dtos.InvoiceListRow> invoices() {
        return invoiceRepo.findForMerchant(mid(),
                List.of(Enums.InvoiceStatus.ISSUED, Enums.InvoiceStatus.PARTIALLY_PAID));
    }

    @GetMapping("/invoices/{id}")
    public Dtos.InvoiceDetailRow invoiceDetail(@PathVariable String id) {
        var invoice = invoiceRepo.findById(id).orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!invoice.getMerchant().getId().equals(mid())) {
            throw new NotFoundException("Invoice not found");
        }
        return b2bService.invoiceDetail(id);
    }

    @PostMapping("/invoices/{id}/pay")
    public Dtos.InvoicePayResult payInvoice(@PathVariable String id,
                                            @RequestBody(required = false) Dtos.PayInvoiceRequest req,
                                            @RequestHeader(value = "X-Sim-Scenario", required = false) String scenarioHeader) {
        com.mipay.sme.security.MoneyGuard.requireAdmin(com.mipay.sme.security.Current.get());
        String route = req == null || req.route() == null ? null : req.route().name();
        String outcome = req == null ? null : req.outcome();
        var scenario = bankSimulator.parse(scenarioHeader);
        if (props.isSimulatedEnvironment() && bankSimulator.shouldAutoRun(scenario)) {
            Enums.InvoicePaymentRoute routeValue = route == null
                    ? Enums.InvoicePaymentRoute.BOOK_TRANSFER
                    : Enums.InvoicePaymentRoute.valueOf(route);
            Dtos.InvoicePending initiated = b2bService.initiateInvoicePaymentAsync(mid(), id, routeValue);
            bankSimulator.scheduleInvoiceOutcome(initiated.invoicePaymentId(),
                    scenario == null ? bankSimulator.defaultScenario() : scenario);
            return initiated;
        }
        return b2bService.payInvoice(mid(), id, route, outcome);
    }

    @PostMapping("/invoices/{id}/retry-timeout")
    public Dtos.InvoicePayResult retryTimeout(@PathVariable String id,
                                              @RequestBody(required = false) Dtos.PayInvoiceRequest req) {
        return b2bService.retryTimedOut(mid(), id, req == null ? null : req.outcome());
    }

    // ------------------------------------------------------------------ catalogue / orders (M13-M15)

    @GetMapping("/catalogue")
    public List<Dtos.CatalogueRow> catalogue() {
        return catalogItemRepo.catalogueForMerchants(Enums.ItemStatus.APPROVED);
    }

    @PostMapping("/orders")
    public Dtos.OrderDetailRow placeOrder(@Valid @RequestBody Dtos.CreateOrderRequest req) {
        return b2bService.placeOrder(mid(), req);
    }

    @GetMapping("/orders")
    public List<Dtos.OrderRow> orders() {
        return orderRepo.findForMerchant(mid());
    }

    @GetMapping("/orders/{id}")
    public Dtos.OrderDetailRow orderDetail(@PathVariable String id) {
        return b2bService.orderDetail(id);
    }

    // ------------------------------------------------------------------ advance (M20-M25)

    @GetMapping("/lenders")
    public List<Dtos.LenderRow> lenders() {
        return viewsService.lenders();
    }

    @PostMapping("/advance/consent")
    public Dtos.ConsentRow consent(@Valid @RequestBody Dtos.ConsentRequest req) {
        return advanceService.grantConsent(mid(), req.lenderId());
    }

    @PostMapping("/advance/request")
    public Dtos.AssessmentView requestAdvance(@Valid @RequestBody Dtos.RequestAdvanceRequest req) {
        return advanceService.requestAssessment(mid(), req.lenderId(), req.amount());
    }

    @PostMapping("/advance/{id}/accept")
    public Dtos.Message acceptAdvance(@PathVariable String id) {
        com.mipay.sme.security.MoneyGuard.requireAdmin(com.mipay.sme.security.Current.get());
        return advanceService.accept(mid(), id);
    }

    /** Sandbox-only: simulate the lender disbursement for demo purposes (labelled). */
    @PostMapping("/advance/{id}/simulate-fund")
    public Dtos.Message simulateFund(@PathVariable String id) {
        var advance = advanceRepo.findByIdAndMerchantId(id, mid())
                .orElseThrow(() -> new NotFoundException("Advance not found"));
        return advanceService.fund(advance.getId(), actorId(), actorRole());
    }

    @GetMapping("/advance")
    public Dtos.AdvanceDetailView currentAdvance() {
        return advanceService.currentAdvance(mid());
    }

    @GetMapping("/advance/eligibility")
    public Dtos.EligibilityView eligibility() {
        return advanceService.eligibility(mid());
    }

    @GetMapping("/advance/consents")
    public List<Dtos.ConsentRow> consents() {
        return consentRepo.rowsForMerchant(mid()); // single projection query, no N+1, no proxy reads
    }

    @PostMapping("/advance/consents/{id}/withdraw")
    public Dtos.Message withdrawConsent(@PathVariable String id) {
        return advanceService.withdrawConsent(mid(), id);
    }

    // ------------------------------------------------------------------ inventory / khata / tabs / statements / settings

    @GetMapping("/inventory")
    public List<Dtos.InventoryRow> inventory() {
        List<com.mipay.sme.domain.ops.InventoryItem> items = inventoryItemRepo.findByMerchantIdAndStatus(
                mid(), Enums.InventoryItemStatus.ACTIVE);
        var openAlerts = stockAlertRepo.findByMerchantIdAndResolvedFalse(mid()).stream()
                .collect(Collectors.toMap(a -> a.getItemId(), a -> a.getAlertType().name()));
        return items.stream()
                .map(item -> new Dtos.InventoryRow(item.getId(), item.getName(), item.getUnit(),
                        item.getCurrentStock(), item.getReorderLevel(),
                        openAlerts.getOrDefault(item.getId(), stockState(item))))
                .toList();
    }

    private String stockState(com.mipay.sme.domain.ops.InventoryItem item) {
        return item.getReorderLevel() != null && item.getCurrentStock().compareTo(item.getReorderLevel()) <= 0
                ? "LOW_STOCK" : "OK";
    }

    @PostMapping("/inventory")
    public Dtos.InventoryRow addItem(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.get("name"));
        String unit = (String) body.getOrDefault("unit", "unit");
        BigDecimal level = new BigDecimal(String.valueOf(body.getOrDefault("reorderLevel", "0")));
        BigDecimal stock = new BigDecimal(String.valueOf(body.getOrDefault("openingStock", "0")));
        return inventoryService.addItem(mid(), name, unit, level, stock);
    }

    @PostMapping("/inventory/adjust")
    public Dtos.InventoryRow adjust(@Valid @RequestBody Dtos.StockAdjustRequest req) {
        return inventoryService.adjust(mid(), req.itemId(), req.movementType(), req.quantity(), req.reference());
    }

    @GetMapping("/inventory/alerts")
    public List<Dtos.StockAlertRow> alerts() {
        var items = inventoryItemRepo.findByMerchantId(mid()).stream()
                .collect(Collectors.toMap(com.mipay.sme.domain.ops.InventoryItem::getId,
                        com.mipay.sme.domain.ops.InventoryItem::getName));
        return stockAlertRepo.findByMerchantIdOrderByCreatedAtDesc(mid()).stream()
                .map(a -> new Dtos.StockAlertRow(a.getId(), items.getOrDefault(a.getItemId(), "?"),
                        a.getAlertType().name(), a.getResolved(), a.getCreatedAt()))
                .toList();
    }

    @GetMapping("/inventory/rules")
    public List<Dtos.ReorderRow> reorderRules() {
        var rules = reorderRuleRepo.findByMerchantId(mid());
        var items = inventoryItemRepo.findByMerchantId(mid()).stream()
                .collect(Collectors.toMap(com.mipay.sme.domain.ops.InventoryItem::getId,
                        com.mipay.sme.domain.ops.InventoryItem::getName));
        return rules.stream()
                .map(rule -> new Dtos.ReorderRow(rule.getId(), items.getOrDefault(rule.getItemId(), "?"),
                        rule.getReorderLevel(), rule.getPreferredSupplierId(),
                        supplierName(rule.getPreferredSupplierId()), rule.getOrderQuantity()))
                .toList();
    }

    private String supplierName(String supplierId) {
        return supplierRepo.findById(supplierId == null ? "" : supplierId)
                .map(com.mipay.sme.domain.core.Supplier::getBusinessName).orElse(null);
    }

    @PostMapping("/inventory/{itemId}/reorder")
    public Dtos.Message reorder(@PathVariable String itemId) {
        String orderId = inventoryService.reorder(mid(), itemId);
        return new Dtos.Message("One-tap reorder placed for supplier - order " + orderId, null);
    }

    // khata

    @GetMapping("/khata")
    public List<Dtos.KhataRow> khata() {
        var accounts = khataRepo.findByMerchantIdOrderByCreatedAtDesc(mid());
        Map<String, BigDecimal> outstanding = khataEntryRepo.outstandingGroupByKhata().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (BigDecimal) r[1]));
        return accounts.stream()
                .map(k -> new Dtos.KhataRow(k.getId(), k.getCustomerName(), k.getCustomerPhone(),
                        outstanding.getOrDefault(k.getId(), BigDecimal.ZERO), k.getStatus().name()))
                .toList();
    }

    @PostMapping("/khata")
    public Dtos.KhataRow createKhata(@Valid @RequestBody Dtos.KhataAccountRequest req) {
        KhataAccount account = new KhataAccount();
        account.setMerchantId(mid());
        account.setCustomerName(req.customerName());
        account.setCustomerPhone(req.customerPhone());
        khataRepo.save(account);
        return new Dtos.KhataRow(account.getId(), account.getCustomerName(), account.getCustomerPhone(),
                BigDecimal.ZERO, account.getStatus().name());
    }

    @GetMapping("/khata/{id}/entries")
    public List<Dtos.KhataEntryRow> khataEntries(@PathVariable String id) {
        return khataEntryRepo.findByKhataIdOrderByCreatedAtDesc(id).stream()
                .map(e -> new Dtos.KhataEntryRow(e.getId(), e.getEntryType().name(), e.getAmount(), e.getDueDate(),
                        e.getSettledAt(), e.getCreatedAt()))
                .toList();
    }

    @PostMapping("/khata/{id}/entries")
    public Dtos.KhataEntryRow addKhataEntry(@PathVariable String id, @Valid @RequestBody Dtos.KhataEntryRequest req) {
        KhataAccount account = khataRepo.findById(id).orElseThrow(() -> new NotFoundException("Khata not found"));
        if (!account.getMerchantId().equals(mid())) {
            throw new NotFoundException("Khata not found");
        }
        KhataEntry entry = new KhataEntry();
        entry.setKhataId(id);
        entry.setEntryType(req.entryType());
        entry.setAmount(req.amount());
        entry.setDueDate(req.dueDate());
        khataEntryRepo.save(entry);
        if (req.entryType() == Enums.KhataEntryType.PAYMENT) {
            entry.setSettledAt(Instant.now());
            khataEntryRepo.save(entry);
            closeIfSettled(account);
        }
        return new Dtos.KhataEntryRow(entry.getId(), entry.getEntryType().name(), entry.getAmount(),
                entry.getDueDate(), entry.getSettledAt(), entry.getCreatedAt());
    }

    private void closeIfSettled(KhataAccount account) {
        BigDecimal outstanding = khataEntryRepo.findByKhataIdOrderByCreatedAtDesc(account.getId()).stream()
                .map(e -> e.getEntryType() == Enums.KhataEntryType.CHARGE ? e.getAmount() : e.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (outstanding.signum() <= 0) {
            account.setStatus(Enums.KhataStatus.SETTLED);
            khataRepo.save(account);
        }
    }

    @PostMapping("/khata/{id}/remind")
    public Dtos.RemindResult remindKhata(@PathVariable String id) {
        KhataAccount account = khataRepo.findById(id).orElseThrow(() -> new NotFoundException("Khata not found"));
        if (!account.getMerchantId().equals(mid())) {
            throw new NotFoundException("Khata not found");
        }
        BigDecimal outstanding = khataEntryRepo.findByKhataIdOrderByCreatedAtDesc(id).stream()
                .map(e -> e.getEntryType() == Enums.KhataEntryType.CHARGE ? e.getAmount() : e.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sendRemind("tab_due", account.getCustomerPhone(),
                Map.of("customer", account.getCustomerName(), "amount", outstanding.toPlainString()));
    }

    // tabs

    @GetMapping("/tabs")
    public List<Dtos.TabRow> tabs() {
        return tabRepo.findByMerchantIdOrderByCreatedAtDesc(mid()).stream()
                .map(t -> new Dtos.TabRow(t.getId(), t.getCustomerName(), t.getCustomerPhone(),
                        t.getTotalAmount(), t.getStatus().name(), t.getDueDate()))
                .toList();
    }

    /** Two-tap tab creation (M36): name + amount; one more tap for the due date. */
    @PostMapping("/tabs")
    public Dtos.TabRow createTab(@Valid @RequestBody Dtos.TabCreateRequest req) {
        Tab tab = new Tab();
        tab.setMerchantId(mid());
        tab.setCustomerName(req.customerName());
        tab.setCustomerPhone(req.customerPhone());
        tab.setTotalAmount(req.amount());
        tab.setDueDate(req.dueDate());
        tab.setStatus(Enums.TabStatus.OPEN);
        tabRepo.save(tab);
        TabEntry entry = new TabEntry();
        entry.setTabId(tab.getId());
        entry.setAmount(req.amount());
        entry.setDescription(req.description());
        tabEntryRepo.save(entry);
        return new Dtos.TabRow(tab.getId(), tab.getCustomerName(), tab.getCustomerPhone(),
                tab.getTotalAmount(), tab.getStatus().name(), tab.getDueDate());
    }

    @GetMapping("/tabs/{id}/entries")
    public List<Dtos.TabEntryRow> tabEntries(@PathVariable String id) {
        return tabEntryRepo.findByTabIdOrderByCreatedAtDesc(id).stream()
                .map(e -> new Dtos.TabEntryRow(e.getId(), e.getAmount(), e.getDescription(), e.getCreatedAt()))
                .toList();
    }

    @PostMapping("/tabs/{id}/entries")
    public Dtos.TabEntryRow addTabEntry(@PathVariable String id, @Valid @RequestBody Dtos.TabAddEntryRequest req) {
        Tab tab = tabRepo.findById(id).orElseThrow(() -> new NotFoundException("Tab not found"));
        if (!tab.getMerchantId().equals(mid())) {
            throw new NotFoundException("Tab not found");
        }
        TabEntry entry = new TabEntry();
        entry.setTabId(id);
        entry.setAmount(req.amount());
        entry.setDescription(req.description());
        tabEntryRepo.save(entry);
        tab.setTotalAmount(tab.getTotalAmount().add(req.amount()));
        tabRepo.save(tab);
        return new Dtos.TabEntryRow(entry.getId(), entry.getAmount(), entry.getDescription(), entry.getCreatedAt());
    }

    @PostMapping("/tabs/{id}/settle")
    public Dtos.TabRow settleTab(@PathVariable String id) {
        Tab tab = tabRepo.findById(id).orElseThrow(() -> new NotFoundException("Tab not found"));
        if (!tab.getMerchantId().equals(mid())) {
            throw new NotFoundException("Tab not found");
        }
        tab.setStatus(Enums.TabStatus.PAID);
        tab.setSettledAt(java.time.Instant.now());
        tabRepo.save(tab);
        auditService.record(actorId(), actorRole(), "tab_settled", "tab", tab.getId(),
                "OPEN", "PAID", null, false, null);
        return new Dtos.TabRow(tab.getId(), tab.getCustomerName(), tab.getCustomerPhone(),
                tab.getTotalAmount(), tab.getStatus().name(), tab.getDueDate());
    }

    @PostMapping("/tabs/{id}/remind")
    public Dtos.RemindResult remindTab(@PathVariable String id) {
        Tab tab = tabRepo.findById(id).orElseThrow(() -> new NotFoundException("Tab not found"));
        if (!tab.getMerchantId().equals(mid())) {
            throw new NotFoundException("Tab not found");
        }
        return sendRemind("tab_due", tab.getCustomerPhone(),
                Map.of("customer", tab.getCustomerName(), "amount", tab.getTotalAmount().toPlainString()));
    }

    /** WhatsApp/SMS reminder render from templates (M37, C40, CU02). */
    @Transactional
    Dtos.RemindResult sendRemind(String eventCode, String to, Map<String, String> vars) {
        var template = templateRepo.findByEventCodeAndChannelAndStatus(eventCode, Enums.TemplateChannel.WHATSAPP,
                Enums.TemplateStatus.ACTIVE)
                .or(() -> templateRepo.findByEventCodeAndChannelAndStatus(eventCode, Enums.TemplateChannel.SMS,
                        Enums.TemplateStatus.ACTIVE));
        String channel = template.map(t -> t.getChannel().name()).orElse("SMS");
        String body = template.map(t -> t.getBody()).orElse(
                "Hi {customer}, you have N${amount} outstanding. Pay when you can (SIMULATED reminder).");
        for (Map.Entry<String, String> e : vars.entrySet()) {
            body = body.replace("{" + e.getKey() + "}", e.getValue());
        }
        auditService.sim(actorId(), actorRole(), "reminder_sent", "customer", to,
                channel + ": " + body, null);
        return new Dtos.RemindResult(channel, to, body, "SIMULATED notification (Eco SB p.8)");
    }

    // statements

    @GetMapping("/statements")
    public List<Dtos.StatementRow> statements() {
        return statementService.list("merchant", mid());
    }

    @PostMapping("/statements")
    public Dtos.StatementRow generateStatement(@Valid @RequestBody Dtos.GenerateStatementRequest req) {
        return statementService.generate("merchant", mid(), req.from(), req.to(), actorId());
    }

    @GetMapping(value = "/statements/{id}/download", produces = MediaType.TEXT_PLAIN_VALUE)
    public String downloadStatement(@PathVariable String id) {
        var s = statementService.statementFile(id, "merchant", mid());
        return statementService.downloadText("merchant", mid(), s.getPeriodStart(), s.getPeriodEnd());
    }

    // settings (M27)

    @GetMapping("/settings")
    public Dtos.SettingsView settings() {
        return viewsService.settings(mid());
    }

    @GetMapping("/devices")
    public List<Dtos.DeviceRow> devices() {
        Merchant m = merchantRepo.findById(mid()).orElseThrow(() -> new NotFoundException("Merchant not found"));
        return sessionRepo.findByUserIdOrderByCreatedAtDesc(m.getUserId(), PageRequest.of(0, 20)).stream()
                .map(s -> new Dtos.DeviceRow(s.getId(), s.getDeviceFingerprint(), s.getMfaMethod(),
                        s.getExpiresAt(), s.getCreatedAt()))
                .toList();
    }
}
