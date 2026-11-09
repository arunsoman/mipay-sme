package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.PaymentEventType;
import com.mipay.sme.domain.payments.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, String> {

    /** Duplicate-callback detection (TC-ACC-04). */
    boolean existsByPaymentIdAndEventTypeAndPayloadHash(String paymentId, PaymentEventType eventType, String payloadHash);

    List<PaymentEvent> findByPaymentIdOrderByCreatedAtAsc(String paymentId);
}
