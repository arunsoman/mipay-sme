package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycList;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycListRepository extends JpaRepository<KycList, String> {
}
