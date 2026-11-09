package com.mipay.sme.repository;

import com.mipay.sme.domain.admin.Backup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BackupRepository extends JpaRepository<Backup, String> {

    List<Backup> findAllByOrderByStartedAtDesc();

    Optional<Backup> findTopByOrderByStartedAtDesc();
}
