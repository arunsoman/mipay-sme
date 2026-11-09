package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.QrStatus;
import com.mipay.sme.domain.payments.ShortCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShortCodeRepository extends JpaRepository<ShortCode, String> {

    Optional<ShortCode> findTopByMerchantIdAndStatusOrderByCreatedAtDesc(String merchantId, QrStatus status);

    boolean existsByShortCode(String shortCode);

    Optional<ShortCode> findByShortCodeAndStatus(String shortCode, QrStatus status);

    Optional<ShortCode> findTopByShortCodeIgnoreCaseAndStatus(String shortCode, QrStatus status);
}
