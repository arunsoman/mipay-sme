package com.mipay.sme.repository;

import com.mipay.sme.domain.credit.PortfolioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, String> {

    List<PortfolioSnapshot> findByLenderIdOrderBySnapshotDateDesc(String lenderId);
}
