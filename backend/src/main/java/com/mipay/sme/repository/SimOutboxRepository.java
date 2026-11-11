package com.mipay.sme.repository;

import com.mipay.sme.domain.org.SimOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimOutboxRepository extends JpaRepository<SimOutbox, String> {

    List<SimOutbox> findAllByOrderByCreatedAtDesc();
}
