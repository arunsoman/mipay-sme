package com.mipay.sme.repository;

import com.mipay.sme.domain.ledger.LimitProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LimitProfileRepository extends JpaRepository<LimitProfile, String> {

    Optional<LimitProfile> findByMerchantId(String merchantId);

    Optional<LimitProfile> findFirstByMerchantIdIsNull();

    List<LimitProfile> findAllByOrderByMerchantIdAsc();
}
