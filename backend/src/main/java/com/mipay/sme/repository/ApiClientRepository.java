package com.mipay.sme.repository;

import com.mipay.sme.domain.admin.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiClientRepository extends JpaRepository<ApiClient, String> {

    List<ApiClient> findAllByOrderByCreatedAtDesc();

    Optional<ApiClient> findByClientId(String clientId);
}
