package com.mipay.sme.repository;

import com.mipay.sme.domain.credit.ArrearsCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArrearsCaseRepository extends JpaRepository<ArrearsCase, String> {

    List<ArrearsCase> findByLenderIdOrderByOpenedAtDesc(String lenderId);

    List<ArrearsCase> findByAdvanceId(String advanceId);
}
