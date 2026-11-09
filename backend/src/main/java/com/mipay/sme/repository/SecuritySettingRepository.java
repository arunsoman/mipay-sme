package com.mipay.sme.repository;

import com.mipay.sme.domain.core.SecuritySetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SecuritySettingRepository extends JpaRepository<SecuritySetting, String> {

    Optional<SecuritySetting> findBySettingKey(String settingKey);

    List<SecuritySetting> findAllByOrderBySettingKeyAsc();

    /** Pessimistic row lock - the DB-level mutex for batch serialisation (e.g. recon runs).
     *  A JDBC lock wait, not a JVM monitor: virtual threads never pin a carrier on it,
     *  and it is cluster-safe on both the H2 test profile and Postgres. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from SecuritySetting s where s.settingKey = :key")
    Optional<SecuritySetting> lockByKey(@org.springframework.data.repository.query.Param("key") String key);
}
