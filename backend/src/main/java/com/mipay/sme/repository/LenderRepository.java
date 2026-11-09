package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.LenderStatus;
import com.mipay.sme.domain.credit.Lender;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LenderRepository extends JpaRepository<Lender, String> {

    Optional<Lender> findByUserId(String userId);

    Optional<Lender> findFirstByStatus(LenderStatus status);

    List<Lender> findAllByOrderByName();
}
