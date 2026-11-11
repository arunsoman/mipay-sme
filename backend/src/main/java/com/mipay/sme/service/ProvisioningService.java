package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.repository.AccountMappingRepository;
import com.mipay.sme.repository.BalanceAccountRepository;
import com.mipay.sme.repository.ConnectorRepository;
import com.mipay.sme.repository.QrCodeRepository;
import com.mipay.sme.repository.ShortCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Balance account + issuer mapping + QR + short code provisioning (E04/E05,
 * M07, M43) after an approval — shared by the legacy approval flow and the
 * tree onboarding decision ([C1], plan §"applyRiskRating moves").
 */
@Service
@RequiredArgsConstructor
public class ProvisioningService {

    private final BalanceAccountRepository accountRepo;
    private final AccountMappingRepository mappingRepo;
    private final ConnectorRepository connectorRepo;
    private final QrCodeRepository qrRepo;
    private final ShortCodeRepository shortCodeRepo;

    private final SecureRandom random = new SecureRandom();

    /** Supplier receiving account: credit side of the three-ledger rule (BC p.9, TC-B2B-05). */
    @Transactional
    public void provisionSupplier(Supplier s) {
        com.mipay.sme.domain.ledger.BalanceAccount account = accountRepo
                .findByOwnerTypeAndOwnerId(Enums.OwnerType.SUPPLIER, s.getId())
                .orElseGet(com.mipay.sme.domain.ledger.BalanceAccount::new);
        account.setOwnerType(Enums.OwnerType.SUPPLIER);
        account.setOwnerId(s.getId());
        account.setCurrency("NAD");
        account.setStatus(Enums.AccountStatus.ACTIVE);
        account.setIssuerAccountRef("NDB-S-" + randomDigits(6));
        account.setLegalHolder("issuer (bank holds float)");
        accountRepo.save(account);
    }

    @Transactional
    public void provision(Merchant m) {
        com.mipay.sme.domain.ledger.BalanceAccount account = accountRepo
                .findByOwnerTypeAndOwnerId(Enums.OwnerType.MERCHANT, m.getId())
                .orElseGet(com.mipay.sme.domain.ledger.BalanceAccount::new);
        account.setOwnerType(Enums.OwnerType.MERCHANT);
        account.setOwnerId(m.getId());
        account.setCurrency("NAD");
        account.setStatus(Enums.AccountStatus.ACTIVE);
        account.setIssuerAccountRef("NDB-M-" + randomDigits(6));
        account.setLegalHolder("issuer (bank holds float)");
        accountRepo.save(account);

        com.mipay.sme.domain.ledger.Connector bank = connectorRepo
                .findFirstByConnectorTypeAndStatusOrderByCreatedAtDesc(
                        Enums.ConnectorType.BANK_ISSUER, Enums.ConnectorStatus.ACTIVE).orElse(null);
        com.mipay.sme.domain.ledger.AccountMapping mapping = new com.mipay.sme.domain.ledger.AccountMapping();
        mapping.setMerchantId(m.getId());
        mapping.setBalanceAccountId(account.getId());
        mapping.setConnectorId(bank == null ? null : bank.getId());
        mapping.setIssuerAccountRef(account.getIssuerAccountRef());
        mapping.setLegalHolder(account.getLegalHolder());
        mappingRepo.save(mapping);

        com.mipay.sme.domain.payments.QrCode qr = new com.mipay.sme.domain.payments.QrCode();
        qr.setMerchantId(m.getId());
        qr.setQrPayload("mipay://qr/" + m.getId());
        qr.setStatus(Enums.QrStatus.ACTIVE);
        qrRepo.save(qr);

        com.mipay.sme.domain.payments.ShortCode code = new com.mipay.sme.domain.payments.ShortCode();
        code.setMerchantId(m.getId());
        code.setShortCode("MP" + randomDigits(6));
        while (shortCodeRepo.existsByShortCode(code.getShortCode())) {
            code.setShortCode("MP" + randomDigits(6));
        }
        code.setStatus(Enums.QrStatus.ACTIVE);
        shortCodeRepo.save(code);
    }

    private String randomDigits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }
}
