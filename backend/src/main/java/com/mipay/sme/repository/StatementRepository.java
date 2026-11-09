package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.Statement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatementRepository extends JpaRepository<Statement, String> {

    List<Statement> findByOwnerTypeAndOwnerIdOrderByGeneratedAtDesc(String ownerType, String ownerId);
}
