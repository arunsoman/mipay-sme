package com.mipay.sme.repository;

import com.mipay.sme.domain.credit.ConsentWithdrawal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentWithdrawalRepository extends JpaRepository<ConsentWithdrawal, String> { }
