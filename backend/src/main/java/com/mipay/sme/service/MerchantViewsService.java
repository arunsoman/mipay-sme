package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.DeviceSession;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.web.Dtos;
import com.mipay.sme.repository.CardTerminalRepository;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.MerchantSubledgerRepository;
import com.mipay.sme.repository.PaymentRepository;
import com.mipay.sme.repository.QrCodeRepository;
import com.mipay.sme.repository.ShortCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Read-only views for the merchant app: M04 dashboard, M05 balance detail,
 * M06 accept-payment info, M27 settings/devices screens. All reads are
 * single queries (aggregates or paged lists) - no lazy navigation, no N+1.
 */
@Service
@RequiredArgsConstructor
public class MerchantViewsService {

    private final PaymentRepository paymentRepo;
    private final com.mipay.sme.repository.InvoicePaymentRepository invoicePaymentRepo;
    private final MerchantSubledgerRepository subRepo;
    private final LedgerService ledgerService;
    private final MerchantRepository merchantRepo;
    private final QrCodeRepository qrRepo;
    private final ShortCodeRepository shortCodeRepo;
    private final CardTerminalRepository terminalRepo;
    private final DeviceSessionRepository sessionRepo;
    private final LenderRepository lenderRepo;

    @Transactional(readOnly = true)
    public Dtos.DashboardView dashboard(String merchantId) {
        Dtos.BalanceView balance = ledgerService.balanceView(merchantId);

        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        BigDecimal salesToday = paymentRepo.confirmedSumInRange(merchantId, Enums.PaymentStatus.CONFIRMED, dayStart, dayEnd);
        BigDecimal costsToday = invoicePaymentRepo.confirmedSumInRange(merchantId, Enums.InvoicePaymentStatus.CONFIRMED, dayStart, dayEnd);
        BigDecimal profitToday = salesToday.subtract(costsToday).setScale(2, java.math.RoundingMode.HALF_UP);

        List<Dtos.RecentRow> recent = paymentRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId,
                        PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent().stream()
                .map(p -> new Dtos.RecentRow(
                        (p.getChannel() == null ? "payment" : p.getChannel().name()) + " payment",
                        p.getAmount(), p.getCreatedAt(), p.getStatus().name()))
                .toList();

        return new Dtos.DashboardView(balance.available(), balance.pending(), balance.held(), balance.owed(),
                salesToday, costsToday, profitToday, recent);
    }

    @Transactional(readOnly = true)
    public List<Dtos.LedgerRow> ledgerPage(String merchantId, int page, int size) {
        // constructor projection returns ready rows - no entity hydration (TC-PERF-03)
        return subRepo.findByOwnerIdOrderByCreatedAtDesc(merchantId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Dtos.PayInfo payInfo(String merchantId) {
        String qr = qrRepo.findTopByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, Enums.QrStatus.ACTIVE)
                .map(q -> q.getQrPayload()).orElse(null);
        String code = shortCodeRepo.findTopByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, Enums.QrStatus.ACTIVE)
                .map(s -> s.getShortCode()).orElse(null);
        String terminal = terminalRepo.findByMerchantId(merchantId).stream()
                .filter(t -> t.getStatus() == Enums.TerminalStatus.ACTIVE)
                .findFirst().map(t -> t.getTerminalId()).orElse(null);
        return new Dtos.PayInfo(qr, code, terminal);
    }

    @Transactional(readOnly = true)
    public Dtos.SettingsView settings(String merchantId) {
        Merchant m = merchantRepo.findById(merchantId).orElseThrow(() -> new com.mipay.sme.common.NotFoundException("Merchant not found"));
        List<Dtos.DeviceRow> devices = sessionRepo.findByUserIdOrderByCreatedAtDesc(m.getUserId(),
                        PageRequest.of(0, 10)).stream()
                .map(s -> new Dtos.DeviceRow(s.getId(), s.getDeviceFingerprint(), s.getMfaMethod(),
                        s.getExpiresAt(), s.getCreatedAt()))
                .toList();
        Dtos.PayInfo info = payInfo(merchantId);
        Dtos.MerchantApprovalRow profile = new Dtos.MerchantApprovalRow(m.getId(), m.getBusinessName(),
                m.getOwnerName(), m.getOwnerPhone(), m.getBusinessType(), m.getRegistrationNumber(),
                m.getKycStatus(), m.getApprovalStatus(), m.getCreatedAt(),
                m.getKycRiskRating(), m.getKycNextReviewDate());
        return new Dtos.SettingsView(profile, devices, info.shortCode(), info.qrPayload());
    }

    @Transactional(readOnly = true)
    public List<Dtos.LenderRow> lenders() {
        return lenderRepo.findAllByOrderByName().stream()
                .map(l -> new Dtos.LenderRow(l.getId(), l.getName(), l.getLicenceRef()))
                .toList();
    }

    private String compact(String sourceRefId) {
        return sourceRefId == null ? "-" : sourceRefId.length() > 8 ? sourceRefId.substring(0, 8) : sourceRefId;
    }
}
