package com.mipay.sme.domain.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.PrePersist;

/** Key/value store for security, fee-provision and ops settings (E44, C03). */
@Entity
@Table(name = "security_settings")
@Getter @Setter
public class SecuritySetting {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "setting_key", unique = true, nullable = false)
    private String settingKey;

    @Column(name = "setting_value")
    private String settingValue;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
