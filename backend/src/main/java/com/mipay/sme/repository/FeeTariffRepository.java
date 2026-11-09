package com.mipay.sme.repository;

import com.mipay.sme.domain.fees.FeeTariff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeeTariffRepository extends JpaRepository<FeeTariff, String> {

    /** Current tariff for a code (latest effective). */
    Optional<FeeTariff> findTopByCodeOrderByEffectiveFromDesc(String code);

    List<FeeTariff> findByCodeOrderByIdDesc(String code);

    List<FeeTariff> findAllByOrderByCodeAsc();
}
