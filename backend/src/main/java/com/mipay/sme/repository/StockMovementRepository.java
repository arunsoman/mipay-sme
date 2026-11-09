package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, String> { }
