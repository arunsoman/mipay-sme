package com.mipay.sme.web;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.payments.ShortCode;
import com.mipay.sme.security.rbac.PublicEndpoint;
import com.mipay.sme.service.AuditService;
import com.mipay.sme.service.PaymentService;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.ShortCodeRepository;
import com.mipay.sme.web.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer touchpoint simulator (CU01, UAT SB p.9): the customer pays from their
 * own bank app / basic phone / card - no separate wallet. In this UAT build the
 * "customer bank" is a simulated caller of the pay endpoints; every action is
 * labelled SIMULATED and audited.
 */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerController {

    private final PaymentService paymentService;
    private final ShortCodeRepository shortCodeRepo;
    private final MerchantRepository merchantRepo;
    private final AuditService auditService;

    public record CustomerLookup(String merchantId, String merchantName, String shortCode) { }

    @PublicEndpoint(reason = "customer touchpoint lookup - merchant by short code or QR payload")
    @GetMapping("/lookup")
    public CustomerLookup lookup(@RequestParam(required = false) String shortCode,
                                 @RequestParam(required = false) String qrPayload) {
        if (shortCode != null && !shortCode.isBlank()) {
            ShortCode sc = shortCodeRepo.findTopByShortCodeIgnoreCaseAndStatus(shortCode, Enums.QrStatus.ACTIVE)
                    .orElseThrow(() -> new NotFoundException("Short code not found"));
            var merchant = merchantRepo.findById(sc.getMerchantId())
                    .orElseThrow(() -> new NotFoundException("Merchant not found"));
            return new CustomerLookup(merchant.getId(), merchant.getBusinessName(), sc.getShortCode());
        }
        if (qrPayload != null && qrPayload.startsWith("mipay://qr/")) {
            String merchantId = qrPayload.substring("mipay://qr/".length());
            var merchant = merchantRepo.findById(merchantId)
                    .orElseThrow(() -> new NotFoundException("Merchant not found"));
            return new CustomerLookup(merchant.getId(), merchant.getBusinessName(), null);
        }
        throw new NotFoundException("Provide shortCode or qrPayload");
    }

    @PublicEndpoint(reason = "customer touchpoint payment - customer bank pays via short code or QR")
    @PostMapping("/pay")
    public Dtos.PaymentOpResult pay(@Valid @RequestBody Dtos.CustomerPayRequest req) {
        String merchantId;
        Enums.PaymentChannel channel;
        if (req.shortCode() != null && !req.shortCode().isBlank()) {
            ShortCode sc = shortCodeRepo.findByShortCodeAndStatus(req.shortCode(), Enums.QrStatus.ACTIVE)
                    .orElseThrow(() -> new NotFoundException("Short code not found"));
            merchantId = sc.getMerchantId();
            channel = Enums.PaymentChannel.SHORT_CODE;
        } else if (req.qrPayload() != null && req.qrPayload().startsWith("mipay://qr/")) {
            merchantId = req.qrPayload().substring("mipay://qr/".length());
            channel = Enums.PaymentChannel.QR;
        } else {
            throw new NotFoundException("Provide shortCode or qrPayload");
        }
        Dtos.AcceptPaymentRequest accept = new Dtos.AcceptPaymentRequest(req.amount(), channel,
                req.payerPhone(), null, null);
        var payment = paymentService.accept(merchantId, accept);
        Dtos.PaymentOpResult result = paymentService.simulate(merchantId, payment.getId(), "confirm");
        auditService.sim("customer-" + (req.payerPhone() == null ? "unknown" : req.payerPhone()), "CUSTOMER",
                "customer_bank_pay", "payment", payment.getId(),
                "customer paid from own bank app/basic phone", null);
        return result;
    }
}
