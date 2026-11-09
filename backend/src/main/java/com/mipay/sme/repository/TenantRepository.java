package com.mipay.sme.repository;

import com.mipay.sme.domain.core.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> { }
