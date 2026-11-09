package com.mipay.sme.service;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.SecuritySetting;
import com.mipay.sme.domain.fees.FeeTariff;
import com.mipay.sme.domain.fees.FeeTransaction;
import com.mipay.sme.repository.FeeTariffRepository;
import com.mipay.sme.repository.FeeTransactionRepository;
import com.mipay.sme.repository.SecuritySettingRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Fees are applied exactly once per event ((fee_code, reference) unique) and can
 * be reversed (TC-RCN-03, BC p.18). Provision vs contracted labelling follows
 * BC p.15: switching N$0.40 = budgeting provision, sponsor N$0.10 = unquoted.
 */
@Service
@RequiredArgsConstructor
public class FeeService {

    public static final String CODE_CARD_SERVICE = "card_service";
    public static final String CODE_ADVANCE_SERVICE = "advance_service";

    private final FeeTariffRepository tariffRepo;
    private final FeeTransactionRepository feeRepo;
    private final SecuritySettingRepository settingRepo;

    @Transactional
    public void applyOnce(Merchant merchant, String feeCode, BigDecimal baseAmount, String reference) {
        if (feeRepo.existsByFeeCodeAndReference(feeCode, reference)) {
            return; // idempotent: never double-charge
        }
        FeeTariff tariff = tariffRepo.findTopByCodeOrderByEffectiveFromDesc(feeCode)
                .orElseThrow(() -> new NotFoundException("No tariff '" + feeCode + "' configured"));
        BigDecimal amount = tariff.getRateType() == Enums.RateType.PERCENTAGE
                ? baseAmount.multiply(tariff.getValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                : tariff.getValue();
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            return; // zero-value tariffs (e.g. unconfigured advance service fee) are skipped
        }
        FeeTransaction tx = new FeeTransaction();
        tx.setMerchant(merchant);
        tx.setFeeCode(feeCode);
        tx.setAmount(amount);
        tx.setReference(reference);
        tx.setStatus(Enums.FeeStatus.APPLIED);
        feeRepo.save(tx);
    }

    @Transactional
    public void reverse(String feeCode, String reference) {
        feeRepo.findByFeeCodeAndReference(feeCode, reference)
                .filter(tx -> tx.getStatus() == Enums.FeeStatus.APPLIED)
                .ifPresent(tx -> {
                    tx.setStatus(Enums.FeeStatus.REVERSED);
                    feeRepo.save(tx);
                });
    }

    @Transactional(readOnly = true)
    public Dtos.FeePreview preview(BigDecimal amount) {
        FeeTariff card = tariffRepo.findTopByCodeOrderByEffectiveFromDesc(CODE_CARD_SERVICE)
                .orElseThrow(() -> new NotFoundException("No card_service tariff"));
        BigDecimal billed = percentOf(amount, card.getValue());
        BigDecimal externalCost = percentOf(amount, orZero(card.getExternalCost()));
        BigDecimal contribution = billed.subtract(externalCost).max(BigDecimal.ZERO);
        BigDecimal ipsFee = BigDecimal.ZERO.setScale(2);
        BigDecimal switching = setting("switching_fee_provision", "0.40");
        BigDecimal sponsor = setting("sponsor_markup_unquoted", "0.10");
        List<String> labels = List.of(
                "card_service: contracted tariff (merchant pays)",
                "IPS P2M customer fee: N$0 (statutory)",
                "Interchange 0.40%: cost, not revenue",
                "Switching N$0.40: budgeting provision, not contracted (BC p.15)",
                "Sponsor N$0.10: unquoted provision (BC p.15)");
        return new Dtos.FeePreview(amount, billed, externalCost, contribution, ipsFee, switching, sponsor, labels);
    }

    private BigDecimal percentOf(BigDecimal base, BigDecimal pct) {
        return base.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal setting(String key, String defaultValue) {
        return settingRepo.findBySettingKey(key)
                .map(SecuritySetting::getSettingValue)
                .map(v -> {
                    try {
                        return new BigDecimal(v);
                    } catch (NumberFormatException e) {
                        return new BigDecimal(defaultValue);
                    }
                })
                .orElse(new BigDecimal(defaultValue));
    }
}
