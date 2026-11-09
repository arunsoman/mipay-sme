package com.mipay.sme.repository;

import com.mipay.sme.domain.payments.CardTerminal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardTerminalRepository extends JpaRepository<CardTerminal, String> {

    List<CardTerminal> findByMerchantId(String merchantId);
}
