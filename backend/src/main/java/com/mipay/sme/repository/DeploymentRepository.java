package com.mipay.sme.repository;

import com.mipay.sme.domain.admin.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, String> {

    Optional<Deployment> findTopByOrderByDeployedAtDesc();

    List<Deployment> findAllByOrderByDeployedAtDesc();
}
