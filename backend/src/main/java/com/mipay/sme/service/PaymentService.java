package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.domain.payments.PaymentEvent;
import com.mipay.sme.domain.payments.Refund;
import com.mipay.sme.repository.PaymentEventRepository;
import com.mipay.sme.repository.PaymentRepository;
import com.mipay.sme.repository.RefundRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import com.mipay.sme.repository.MerchantRepository;
import java.util.List;

/**
 * Accept-payment lifecycle for QR / short code / card (UAT SB p.9).
 *
 * - Duplicate callbacks are detected via (payment, event_type, payload_hash)
 *   and never move money twice (TC-ACC-04, BC p.18).
 * - Timeout keeps status PENDING so a later final answer still completes (TC-ACC-05).
 * - Card funds become available only after the acquirer releases (TC-ACC-03).
 * - Every simulated bank event is labelled in UI and audit (UAT SB p.16, BC p.17).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final PaymentEventRepository eventRepo;
    private final RefundRepository refundRepo;
    private final MerchantRepository merchantRepo;
    private final LedgerService ledgerService;
    private final FeeService feeService;
    private final InventoryService inventoryService;
    private final ComplianceService complianceService;
    private final AuditService auditService;
    private final AppProperties props;

    private static final String SIM_LABEL = "SIMULATED customer bank callback";

    public Merchant requireApprovedMerchant(String merchantId) {
        Merchant m = merchantRepo.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found"));
        if (m.getApprovalStatus() != Enums.ApprovalStatus.APPROVED) {
            throw new SimException("MERCHANT_NOT_APPROVED",
                    "Merchant approval pending or suspended; payments refused (UAT SB p.8)");
        }
        return m;
    }

    // ------------------------------------------------------------------ accept

    @Transactional
    public Payment accept(String merchantId, Dtos.AcceptPaymentRequest req) {
        Merchant merchant = requireApprovedMerchant(merchantId);

        Payment p = new Payment();
        p.setMerchant(merchant);
        p.setChannel(req.channel());
        p.setAmount(req.amount().setScale(2, java.math.RoundingMode.HALF_UP));
        p.setCurrency("NAD");
        p.setPayerRef(req.payerRef());
        p.setItemId(req.itemId());
        p.setItemQuantity(req.itemQuantity());
        p.setStatus(Enums.PaymentStatus.PENDING);
        p.setIdempotencyKey("pay-" + UUID.randomUUID());
        paymentRepo.save(p);

        // card accepts immediately reserve a pending subledger entry (not spendable, TC-BAL-02)
        if (p.getChannel() == Enums.PaymentChannel.CARD) {
            BalanceAccount account = ledgerService.account(Enums.OwnerType.MERCHANT, merchant.getId());
            ledgerService.postPending(account, Enums.LedgerEntryType.CREDIT, p.getAmount(),
                    Enums.SubledgerSource.CARD, p.getId());
        }

        event(p, Enums.PaymentEventType.INITIATED, "accept",
                "accepted " + p.getChannel() + " payment N$" + p.getAmount());
        auditService.sim(actorId(), actorRole(), "payment_accepted", "payment", p.getId(),
                "channel=" + p.getChannel() + " amount=" + p.getAmount(), null);
        return p;
    }

    // ------------------------------------------------------------------ simulated customer / issuer actions

    @Transactional
    public Dtos.PaymentOpResult simulate(String merchantId, String paymentId, String action) {
        if (action == null || action.isBlank()) {
            throw new SimException("SIM_ACTION", "action required: confirm | duplicate | timeout | fail");
        }
        Payment p = paymentRepo.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        switch (action.toLowerCase()) {
            case "confirm" -> {
                if (p.getStatus() == Enums.PaymentStatus.FAILED) {
                    // late answer after a failure must never resurrect the payment (TC-ACC-05)
                    return new Dtos.PaymentFailed(p.getId(), p.getAmount(),
                            "Payment already failed - late confirmation refused, funds unmoved");
                }
                if (p.getStatus() == Enums.PaymentStatus.CONFIRMED) {
                    return new Dtos.PaymentConfirmed(p.getId(), p.getAmount(), p.getCurrency(),
                            "Already confirmed - duplicate callback absorbed");
                }
                return confirm(p, "Payment confirmed once (custapp callback)");
            }
            case "duplicate" -> {
                boolean seen = eventRepo.existsByPaymentIdAndEventTypeAndPayloadHash(
                        p.getId(), Enums.PaymentEventType.CALLBACK, "confirm");
                if (seen) {
                    event(p, Enums.PaymentEventType.DUPLICATE_IGNORED, "dup",
                            "replay ignored - no second debit/credit");
                    auditService.sim(actorId(), actorRole(), "duplicate_callback_ignored", "payment", p.getId(),
                            "replay of confirm callback", null);
                    return new Dtos.PaymentPending(p.getId(), p.getAmount(),
                            "Duplicate callback ignored - balance unchanged (single-effect guarantee)");
                }
                return confirm(p, "First confirmation recorded");
            }
            case "timeout" -> {
                if (p.getStatus() == Enums.PaymentStatus.CONFIRMED || p.getStatus() == Enums.PaymentStatus.FAILED) {
                    // terminal state already reached - do not write a contradictory timeout event
                    return new Dtos.PaymentPending(p.getId(), p.getAmount(),
                            "Payment already " + p.getStatus() + " - timeout callback ignored");
                }
                event(p, Enums.PaymentEventType.TIMEOUT, "timeout",
                        "bank timeout - stays PENDING until final answer (UAT SB p.15)");
                auditService.sim(actorId(), actorRole(), "bank_timeout", "payment", p.getId(), null, null);
                return new Dtos.PaymentPending(p.getId(), p.getAmount(),
                        "Timeout recorded; payment remains PENDING (pending preserved)");
            }
            case "fail" -> {
                fail(p, "Customer bank returned failure");
                auditService.sim(actorId(), actorRole(), "payment_failed", "payment", p.getId(), null, null);
                return new Dtos.PaymentFailed(p.getId(), p.getAmount(), "Payment failed; hold-free, no funds moved");
            }
            default -> throw new SimException("SIM_ACTION", "Unknown action " + action);
        }
    }

    @Transactional
    public Dtos.PaymentOpResult confirm(Payment p, String label) {
        // Pessimistic row lock re-loads the payment: concurrent callbacks for the
        // same payment serialise here, so the dedup check below is race-free
        // (TC-RACE-01 - exactly one credit, duplicates absorbed, never exceptions).
        Payment locked = paymentRepo.findWithLockById(p.getId())
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        boolean replay = eventRepo.existsByPaymentIdAndEventTypeAndPayloadHash(
                locked.getId(), Enums.PaymentEventType.CALLBACK, "confirm");
        if (replay) {
            event(locked, Enums.PaymentEventType.DUPLICATE_IGNORED, "dup-confirm", "duplicate confirm ignored");
            return new Dtos.PaymentConfirmed(locked.getId(), locked.getAmount(), locked.getCurrency(),
                    "Duplicate callback ignored - single confirmation guarantee");
        }

        p = locked;
        event(p, Enums.PaymentEventType.CALLBACK, "confirm", "customer bank callback received");

        BalanceAccount account = ledgerService.account(Enums.OwnerType.MERCHANT, p.getMerchant().getId());
        if (p.getChannel() == Enums.PaymentChannel.CARD) {
            // two-phase card: promote the pending subledger rows + issuer entry
            ledgerService.confirmPending(p.getMerchant().getId(), p.getId(), Enums.SubledgerSource.CARD,
                    "ACQ-" + p.getId().substring(0, 8), "pay-confirm-" + p.getId());
        } else {
            ledgerService.postConfirmed(account, Enums.LedgerEntryType.CREDIT, p.getAmount(),
                    Enums.SubledgerSource.PAYMENT, p.getId(),
                    "IPS-" + p.getId().substring(0, 8), "pay-confirm-" + p.getId());
        }

        p.setStatus(Enums.PaymentStatus.CONFIRMED);
        p.setConfirmedAt(Instant.now());
        paymentRepo.save(p);
        event(p, Enums.PaymentEventType.CONFIRMED, "confirm", "single confirmation; funds available");

        if (p.getChannel() == Enums.PaymentChannel.CARD) {
            feeService.applyOnce(p.getMerchant(), FeeService.CODE_CARD_SERVICE, p.getAmount(), "pay/" + p.getId());
        }

        if (p.getItemId() != null && p.getItemQuantity() != null) {
            try {
                inventoryService.recordSale(p.getMerchant().getId(), p.getItemId(), p.getItemQuantity(), p.getId());
            } catch (NotFoundException e) {
                log.warn("Stock item {} not found for sale; payment stays valid", p.getItemId());
            }
        }

        complianceService.screenPayment(p.getMerchant(), p);
        auditService.sim(actorId(), actorRole(), "payment_confirmed", "payment", p.getId(),
                "channel=" + p.getChannel() + " amount=N$" + p.getAmount(), null);

        return new Dtos.PaymentConfirmed(p.getId(), p.getAmount(), p.getCurrency(), label);
    }

    @Transactional
    public void fail(Payment p, String reason) {
        if (p.getChannel() == Enums.PaymentChannel.CARD) {
            ledgerService.reversePending(p.getMerchant().getId(), p.getId(), Enums.SubledgerSource.CARD);
        }
        p.setStatus(Enums.PaymentStatus.FAILED);
        paymentRepo.save(p);
        event(p, Enums.PaymentEventType.FAILED, "fail", reason);
    }

    private PaymentEvent event(Payment p, Enums.PaymentEventType type, String payloadHash, String meta) {
        PaymentEvent e = new PaymentEvent();
        e.setPaymentId(p.getId());
        e.setEventType(type);
        // The (payment_id, event_type, payload_hash) unique key is the dedup anchor for
        // CALLBACK/confirm rows; every other event kind carries a unique suffix so
        // repeated replay/timeout events from concurrent callers never collide
        // (TC-RACE-01: duplicate absorb rows are recorded, never rejected).
        e.setPayloadHash(Enums.PaymentEventType.CALLBACK == type
                ? payloadHash
                : payloadHash + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        e.setMeta(meta);
        e.setSimulated(props.isSimulatedEnvironment());
        return eventRepo.save(e);
    }

    // ------------------------------------------------------------------ refund (TC-B2B-09, UAT SB p.15)

    @Transactional
    public Dtos.RefundRow refund(String merchantId, String paymentId, Dtos.RefundRequest req) {
        // Pessimistic row lock serialises concurrent refunds per payment, so the
        // remaining-amount check never double-counts (TC-RACE-10, no over-refund).
        Payment p = paymentRepo.findWithLockById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        if (!p.getMerchant().getId().equals(merchantId)) {
            throw new NotFoundException("Payment not found");
        }
        if (p.getStatus() != Enums.PaymentStatus.CONFIRMED && p.getStatus() != Enums.PaymentStatus.REFUNDED) {
            throw new SimException("REFUND_NOT_ALLOWED", "Only confirmed payments can be refunded");
        }
        BigDecimal remaining = p.getAmount()
                .subtract(refundRepo.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                        .filter(x -> x.getStatus() == Enums.RefundStatus.PROCESSED)
                        .map(Refund::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (req.amount().signum() <= 0 || req.amount().compareTo(remaining) > 0) {
            throw new SimException("REFUND_AMOUNT", "Refund must be positive and not exceed the unrefunded remainder");
        }

        Refund r = new Refund();
        r.setPaymentId(p.getId());
        r.setMerchantId(merchantId);
        r.setAmount(req.amount());
        r.setReason(req.reason());
        r.setStatus(Enums.RefundStatus.PROCESSED);
        refundRepo.save(r);

        BalanceAccount account = ledgerService.account(Enums.OwnerType.MERCHANT, merchantId);
        ledgerService.postConfirmed(account, Enums.LedgerEntryType.DEBIT, req.amount(),
                Enums.SubledgerSource.REFUND, r.getId(),
                "RF-" + r.getId().substring(0, 8), "refund-" + r.getId());

        BigDecimal refundedTotal = remaining.subtract(req.amount());
        if (refundedTotal.signum() == 0) {
            p.setStatus(Enums.PaymentStatus.REFUNDED);
            feeService.reverse(FeeService.CODE_CARD_SERVICE, "pay/" + p.getId());
        }
        paymentRepo.save(p);
        event(p, Enums.PaymentEventType.REFUNDED, req.amount().toPlainString(),
                "refund processed; eligibility base adjusts (TC-ADV-04)");

        auditService.sim(actorId(), actorRole(), "refund_processed", "payment", p.getId(),
                "refund N$" + req.amount(), null);
        return new Dtos.RefundRow(r.getId(), r.getPaymentId(), r.getAmount(), r.getReason(),
                r.getStatus().name(), r.getCreatedAt());
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<Dtos.PaymentRow> history(String merchantId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Payment> rows = paymentRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
        return rows.getContent().stream()
                .map(r -> new Dtos.PaymentRow(r.getId(), r.getChannel() == null ? null : r.getChannel().name(),
                        r.getAmount(), r.getCurrency(), r.getStatus().name(), r.getPayerRef(),
                        r.getCreatedAt(), r.getConfirmedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.SpeakerLogRow> speakerLog(String merchantId) {
        Instant from = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        return paymentRepo.confirmedInRange(merchantId, Enums.PaymentStatus.CONFIRMED, from, Instant.now()).stream()
                .map(r -> new Dtos.SpeakerLogRow(r.getId(), r.getAmount(), r.getConfirmedAt()))
                .toList();
    }

    private String actorId() {
        try {
            return com.mipay.sme.security.Current.get().userId();
        } catch (NotFoundException e) {
            return "system";
        }
    }

    private String actorRole() {
        try {
            return com.mipay.sme.security.Current.get().role();
        } catch (NotFoundException e) {
            return "MACHINE";
        }
    }
}
