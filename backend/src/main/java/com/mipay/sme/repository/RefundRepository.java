package com.mipay.sme.repository;

import com.mipay.sme.domain.payments.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, String> {

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(String paymentId);

    List<Refund> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
