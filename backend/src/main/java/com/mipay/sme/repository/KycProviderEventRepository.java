package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycProviderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KycProviderEventRepository extends JpaRepository<KycProviderEvent, String> {

    Optional<KycProviderEvent> findByProviderAndEventId(String provider, String eventId);
}
