package com.mipay.sme.repository;

import com.mipay.sme.domain.ledger.AccountMapping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountMappingRepository extends JpaRepository<AccountMapping, String> { }
