package com.mipay.sme.repository;

import com.mipay.sme.domain.core.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Filter for simulated-event labelling audit (E34, TC-SET-28). */
    @Query("select a from AuditLog a where (:onlySimulated = false or a.simulated = true) order by a.createdAt desc")
    Page<AuditLog> search(@Param("onlySimulated") boolean onlySimulated, Pageable pageable);
}
