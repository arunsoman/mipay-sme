package com.mipay.sme.repository;

import com.mipay.sme.domain.admin.SimulationScenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SimulationScenarioRepository extends JpaRepository<SimulationScenario, String> {

    List<SimulationScenario> findByEnabledTrueOrderByName();

    List<SimulationScenario> findAllByOrderByNameAsc();

    Optional<SimulationScenario> findByName(String name);
}
