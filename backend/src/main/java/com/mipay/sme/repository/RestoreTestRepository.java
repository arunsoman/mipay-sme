package com.mipay.sme.repository;

import com.mipay.sme.domain.admin.RestoreTest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RestoreTestRepository extends JpaRepository<RestoreTest, String> {

    Optional<RestoreTest> findTopByOrderByTestedAtDesc();

    List<RestoreTest> findAllByOrderByTestedAtDesc();
}
