package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.b2b.Invoice;
import com.mipay.sme.domain.b2b.InvoicePayment;
import com.mipay.sme.domain.b2b.Order;
import com.mipay.sme.domain.b2b.OrderItem;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.ledger.BalanceHold;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.BalanceAccountRepository;
import com.mipay.sme.repository.InvoicePaymentRepository;
import com.mipay.sme.repository.InvoiceRepository;
import com.mipay.sme.repository.OrderItemRepository;
import com.mipay.sme.repository.OrderRepository;
import com.mipay.sme.repository.SupplierCatalogueItemRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

/**
 * B2B stock purchase flow (UAT SB pp.10-11, BC p.9):
 * order -> supplier invoice with unique reference -> pay from balance.
 *
 * - Atomic: one debit, one supplier credit, invoice marked PAID (TC-B2B-02).
 * - Hold secures funds; released on failure; preserved on timeout (TC-B2B-04/07).
 * - Insufficient balance returns InvoiceInsufficient for screen M19 (TC-B2B-05).
 * - Pessimistic lock on the merchant balance account makes concurrent orders
 *   safe: only one can spend the same available amount (TC-B2B-08).
 * - Replayed bank confirmations are absorbed by issuer idempotency keys (TC-B2B-03).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class B2BPaymentService {
    private final MerchantRepository merchantRepo;

    private final InvoiceRepository invoiceRepo;
    private final InvoicePaymentRepository invoicePaymentRepo;
    private final OrderRepository orderRepo;
    private final OrderItemRepository orderItemRepo;
    private final SupplierRepository supplierRepo;
    private final SupplierCatalogueItemRepository catalogueRepo;
    private final BalanceAccountRepository accountRepo;
    private final PaymentService paymentService;
    private final LedgerService ledgerService;
    private final InventoryService inventoryService;
    private final AuditService auditService;
    private final AppProperties props;

    private final SecureRandom random = new SecureRandom();

    private int invoiceSequence = 42;   // demo counter; production would use a DB sequence

    // ------------------------------------------------------------------ order + invoice

    @Transactional
    public Dtos.OrderDetailRow placeOrder(String merchantId, Dtos.CreateOrderRequest req) {
        paymentService.requireApprovedMerchant(merchantId);
        if (req.items() == null || req.items().isEmpty()) {
            throw new SimException("EMPTY_ORDER", "At least one catalogue item is required");
        }

        Supplier supplier = supplierRepo.findById(req.supplierId())
                .orElseThrow(() -> new NotFoundException("Supplier not found"));
        if (supplier.getApprovalStatus() != Enums.ApprovalStatus.APPROVED) {
            throw new SimException("SUPPLIER_NOT_APPROVED", "Supplier is not approved (UAT SB p.8)");
        }

        Order order = new Order();
        order.setMerchant(merchantRepo.getReferenceById(merchantId));
        order.setSupplier(supplier);
        order.setStatus(Enums.OrderStatus.SUBMITTED);
        orderRepo.save(order);

        for (Dtos.CartLine line : req.items()) {
            var item = catalogueRepo.findById(line.catalogueItemId())
                    .orElseThrow(() -> new NotFoundException("Catalogue item not found"));
            if (!supplier.getId().equals(item.getSupplier().getId())) {
                throw new SimException("CATALOGUE_MISMATCH", "Item does not belong to the ordered supplier");
            }
            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getId());
            oi.setCatalogueItemId(item.getId());
            oi.setProductName(item.getProductName());
            oi.setUnit(item.getUnit());
            oi.setQuantity(line.quantity());
            oi.setUnitPrice(item.getUnitPrice());
            orderItemRepo.save(oi);
        }

        return orderDetail(order.getId());
    }

    @Transactional
    public Dtos.InvoiceDetailRow issueInvoice(String supplierId, String orderId) {
        // Pessimistic lock on the order row serialises concurrent issuance attempts,
        // so exactly ONE invoice is issued per order (TC-B2B-01 unique-reference rule,
        // TC-RACE-07); losers fail cleanly with the conflict below.
        Order order = orderRepo.findWithLockById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (!supplierId.equals(order.getSupplier().getId())) {
            throw new NotFoundException("Order not found");
        }
        if (order.getStatus() == Enums.OrderStatus.INVOICED || order.getStatus() == Enums.OrderStatus.FULFILLED) {
            throw new ConflictException("Order already invoiced");
        }
        List<OrderItem> items = orderItemRepo.findByOrderId(orderId);
        BigDecimal total = items.stream()
                .map(i -> i.getUnitPrice().multiply(i.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Invoice invoice = new Invoice();
        invoice.setSupplier(order.getSupplier());
        invoice.setMerchant(order.getMerchant());
        invoice.setOrderId(orderId);
        invoice.setInvoiceNumber("INV-" + java.time.Year.now().getValue() + "-" + String.format("%04d", ++invoiceSequence));
        invoice.setUniqueReference("KAM-" + randomHex4() + "-" + randomHex4());
        invoice.setAmount(total);
        invoice.setStatus(Enums.InvoiceStatus.ISSUED);
        invoiceRepo.save(invoice);

        order.setStatus(Enums.OrderStatus.INVOICED);
        orderRepo.save(order);

        auditService.record("system", "SUPPLIER", "invoice_issued", "invoice", invoice.getId(),
                null, null, "uniqueReference=" + invoice.getUniqueReference(), false, null);
        return invoiceDetail(invoice.getId());
    }

    private String randomHex4() {
        return String.format("%04X", random.nextInt(0x10000));
    }

    // ------------------------------------------------------------------ pay from balance

    @Transactional
    public Dtos.InvoicePayResult payInvoice(String merchantId, String invoiceId, String route, String outcome) {
        Merchant merchant = paymentService.requireApprovedMerchant(merchantId);
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!merchant.getId().equals(invoice.getMerchant().getId())) {
            throw new NotFoundException("Invoice not found");
        }
        if (invoice.getStatus() != Enums.InvoiceStatus.ISSUED && invoice.getStatus() != Enums.InvoiceStatus.PARTIALLY_PAID) {
            throw new ConflictException("Invoice is not payable (status " + invoice.getStatus() + ")");
        }

        Enums.InvoicePaymentRoute routeValue = route == null
                ? Enums.InvoicePaymentRoute.BOOK_TRANSFER
                : Enums.InvoicePaymentRoute.valueOf(route);

        // Pessimistic lock: concurrent invoices cannot both spend the same funds (TC-B2B-08)
        BalanceAccount locked = accountRepo.lockByOwner(Enums.OwnerType.MERCHANT, merchantId)
                .orElseThrow(() -> new NotFoundException("No balance account for merchant"));

        BigDecimal available = ledgerService.availableFor(merchantId);
        if (available.compareTo(invoice.getAmount()) < 0) {
            BigDecimal shortfall = invoice.getAmount().subtract(available);
            auditService.sim(merchantId, "MERCHANT", "b2b_refused_insufficient", "invoice", invoice.getId(),
                    "shortfall N$" + shortfall, null);
            return new Dtos.InvoiceInsufficient(shortfall.setScale(2, java.math.RoundingMode.HALF_UP),
                    "Insufficient balance - no overdraft (UAT SB p.11)");
        }

        InvoicePayment ip = new InvoicePayment();
        ip.setInvoiceId(invoice.getId());
        ip.setMerchantId(merchantId);
        ip.setSupplierId(invoice.getSupplier().getId());
        ip.setAmount(invoice.getAmount());
        ip.setRoute(routeValue);
        ip.setStatus(Enums.InvoicePaymentStatus.HELD);
        ip.setIdempotencyKey("b2b-" + UUID.randomUUID());
        BalanceHold hold = ledgerService.createHold(merchantId, invoice.getAmount(), invoice.getUniqueReference(), 60);
        ip.setHoldId(hold.getId());
        invoicePaymentRepo.save(ip);

        auditService.sim(merchantId, "MERCHANT", "b2b_payment_initiated", "invoice_payment", ip.getId(),
                "route=" + routeValue + " hold=N$" + hold.getAmount(), null);

        return dispatchBankOutcome(invoice, ip, hold, outcome == null ? "confirm" : outcome);
    }

    /**
     * Async initiation for the bank-simulator flows (X-Sim-Scenario / platform
     * default). Same atomic setup as payInvoice - pessimistic lock, availability
     * check, hold, unique invoice route - but the bank outcome is left to
     * BankSimulator's virtual threads instead of a synchronous dispatch, so the
     * TIMEOUT/LATE_CONFIRM scenarios can surface their real timing (TC-B2B-04/07).
     */
    @Transactional
    public Dtos.InvoicePending initiateInvoicePaymentAsync(String merchantId, String invoiceId,
                                                           Enums.InvoicePaymentRoute routeValue) {
        Merchant merchant = paymentService.requireApprovedMerchant(merchantId);
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!merchant.getId().equals(invoice.getMerchant().getId())) {
            throw new NotFoundException("Invoice not found");
        }
        if (invoice.getStatus() != Enums.InvoiceStatus.ISSUED && invoice.getStatus() != Enums.InvoiceStatus.PARTIALLY_PAID) {
            throw new ConflictException("Invoice is not payable (status " + invoice.getStatus() + ")");
        }

        BalanceAccount locked = accountRepo.lockByOwner(Enums.OwnerType.MERCHANT, merchantId)
                .orElseThrow(() -> new NotFoundException("No balance account for merchant"));
        BigDecimal available = ledgerService.availableFor(merchantId);
        if (available.compareTo(invoice.getAmount()) < 0) {
            throw new SimException("INSUFFICIENT_BALANCE",
                    "Insufficient balance - no overdraft (UAT SB p.11) - shortfall N$"
                            + invoice.getAmount().subtract(available).setScale(2, java.math.RoundingMode.HALF_UP));
        }

        InvoicePayment ip = new InvoicePayment();
        ip.setInvoiceId(invoice.getId());
        ip.setMerchantId(merchantId);
        ip.setSupplierId(invoice.getSupplier().getId());
        ip.setAmount(invoice.getAmount());
        ip.setRoute(routeValue);
        ip.setStatus(Enums.InvoicePaymentStatus.HELD);
        ip.setIdempotencyKey("b2b-" + UUID.randomUUID());
        BalanceHold hold = ledgerService.createHold(merchantId, invoice.getAmount(), invoice.getUniqueReference(), 60);
        ip.setHoldId(hold.getId());
        invoicePaymentRepo.save(ip);

        auditService.sim(merchantId, "MERCHANT", "b2b_payment_initiated", "invoice_payment", ip.getId(),
                "route=" + routeValue + " hold=N$" + hold.getAmount() + " scenario=async", null);

        return new Dtos.InvoicePending(ip.getId(), ip.getAmount(),
                "Bank simulator engaged - hold preserved until the final bank answer");
    }

    @Transactional
    public Dtos.InvoicePayResult retryTimedOut(String merchantId, String invoiceId, String outcome) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!invoice.getMerchant().getId().equals(merchantId)) {
            throw new NotFoundException("Invoice not found");
        }
        InvoicePayment ip = invoicePaymentRepo.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
                .filter(x -> x.getStatus() == Enums.InvoicePaymentStatus.TIMED_OUT)
                .findFirst()
                .orElseThrow(() -> new SimException("RETRY_NOT_POSSIBLE", "No timed-out payment to retry"));
        BalanceHold hold = ledgerService.activeHolds(merchantId).stream()
                .filter(h -> ip.getHoldId() != null && ip.getHoldId().equals(h.getId()))
                .findFirst()
                .orElseThrow(() -> new SimException("HOLD_MISSING", "Preserved hold not found"));

        return dispatchBankOutcome(invoice, ip, hold, outcome == null ? "confirm" : outcome);
    }

    private Dtos.InvoicePayResult dispatchBankOutcome(Invoice invoice, InvoicePayment ip, BalanceHold hold, String outcome) {        switch (outcome.toLowerCase()) {
            case "confirm" -> {
                return completePayment(invoice, ip, hold);
            }
            case "timeout" -> {
                ip.setStatus(Enums.InvoicePaymentStatus.TIMED_OUT);
                invoicePaymentRepo.save(ip);
                auditService.sim(ip.getMerchantId(), "MERCHANT", "b2b_bank_timeout", "invoice_payment", ip.getId(),
                        "hold preserved, invoice unpaid until final result (UAT SB p.10)", null);
                return new Dtos.InvoicePending(ip.getId(), ip.getAmount(),
                        "Bank timeout - hold preserved, invoice stays unpaid");
            }
            case "fail" -> {
                ledgerService.releaseHold(hold);
                ip.setStatus(Enums.InvoicePaymentStatus.FAILED);
                invoicePaymentRepo.save(ip);
                auditService.sim(ip.getMerchantId(), "MERCHANT", "b2b_bank_failure", "invoice_payment", ip.getId(),
                        "hold released, invoice unpaid", null);
                return new Dtos.InvoiceFailed(ip.getId(), ip.getAmount(),
                        "Payment failed - hold released, invoice stays unpaid (UAT SB p.15)");
            }
            default -> throw new SimException("SIM_OUTCOME", "outcome must be confirm | timeout | fail");
        }
    }

    @Transactional
    Dtos.InvoicePaid completePayment(Invoice invoice, InvoicePayment ip, BalanceHold hold) {
        // idempotent replay: already confirmed -> no second effect (TC-B2B-03)
        if (ip.getStatus() == Enums.InvoicePaymentStatus.CONFIRMED) {
            return new Dtos.InvoicePaid(ip.getId(), ip.getAmount(), orDash(ip.getBankReference()),
                    "Already confirmed - duplicate callback absorbed");
        }

        BalanceAccount merchantAccount = ledgerService.account(Enums.OwnerType.MERCHANT, ip.getMerchantId());
        BalanceAccount supplierAccount = ledgerService.account(Enums.OwnerType.SUPPLIER, ip.getSupplierId());

        ledgerService.postConfirmed(merchantAccount, Enums.LedgerEntryType.DEBIT, ip.getAmount(),
                Enums.SubledgerSource.SUPPLIER_PAYMENT, ip.getId(),
                "BT-" + invoice.getUniqueReference(), "b2b-debit-" + ip.getId());
        ledgerService.postConfirmed(supplierAccount, Enums.LedgerEntryType.CREDIT, ip.getAmount(),
                Enums.SubledgerSource.SUPPLIER_PAYMENT, ip.getId(),
                "BT-" + invoice.getUniqueReference(), "b2b-credit-" + ip.getId());

        ledgerService.convertHold(hold);
        ip.setStatus(Enums.InvoicePaymentStatus.CONFIRMED);
        ip.setBankReference("BT-2026-" + String.format("%06d", 100000 + random.nextInt(899999)));
        ip.setConfirmedAt(Instant.now());
        invoicePaymentRepo.save(ip);

        invoice.setStatus(Enums.InvoiceStatus.PAID);
        invoiceRepo.save(invoice);

        if (invoice.getOrderId() != null) {
            orderRepo.findById(invoice.getOrderId()).ifPresent(o -> {
                o.setStatus(Enums.OrderStatus.FULFILLED);
                orderRepo.save(o);
            });
            for (OrderItem oi : orderItemRepo.findByOrderId(invoice.getOrderId())) {
                inventoryService.recordDelivery(ip.getMerchantId(), oi.getProductName(), oi.getQuantity(), invoice.getId());
            }
        }

        auditService.sim(ip.getMerchantId(), "MERCHANT", "b2b_payment_confirmed", "invoice_payment", ip.getId(),
                "one debit, one supplier credit, invoice paid - atomic (TC-B2B-02)", null);
        return new Dtos.InvoicePaid(ip.getId(), ip.getAmount(), ip.getBankReference(),
                "One debit, one supplier credit, invoice paid (atomic)");
    }

    private String orDash(String s) {
        return s == null ? "-" : s;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Dtos.OrderDetailRow orderDetail(String orderId) {
        Order order = orderRepo.findById(orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        List<OrderItem> items = orderItemRepo.findByOrderId(orderId);
        BigDecimal total = items.stream()
                .map(i -> i.getUnitPrice().multiply(i.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Dtos.OrderItemRow> itemRows = items.stream()
                .map(i -> new Dtos.OrderItemRow(i.getId(), i.getProductName(), i.getUnit(),
                        i.getQuantity(), i.getUnitPrice(), i.getUnitPrice().multiply(i.getQuantity())))
                .toList();
        return new Dtos.OrderDetailRow(order.getId(), order.getMerchant().getBusinessName(),
                order.getSupplier().getBusinessName(), order.getStatus().name(), order.getCreatedAt(),
                itemRows, total);
    }

    @Transactional(readOnly = true)
    public Dtos.InvoiceDetailRow invoiceDetail(String invoiceId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        List<Dtos.InvoicePaymentRow> payments = invoicePaymentRepo.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
                .map(ipx -> new Dtos.InvoicePaymentRow(ipx.getId(), ipx.getAmount(), ipx.getRoute().name(),
                        ipx.getStatus().name(), ipx.getBankReference(), ipx.getCreatedAt(), ipx.getConfirmedAt()))
                .toList();
        return new Dtos.InvoiceDetailRow(invoice.getId(), invoice.getInvoiceNumber(), invoice.getUniqueReference(),
                invoice.getSupplier().getBusinessName(), invoice.getMerchant().getBusinessName(),
                invoice.getAmount(), invoice.getCurrency(), invoice.getStatus().name(),
                invoice.getIssuedAt(), payments);
    }
}
