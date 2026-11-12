package com.mipay.sme.repository;

import com.mipay.sme.domain.core.AuthOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthOutboxRepository extends JpaRepository<AuthOutbox, String> {
    Optional<AuthOutbox> findByDedupeKey(String dedupeKey);
    long countByState(AuthOutbox.State state);
}
