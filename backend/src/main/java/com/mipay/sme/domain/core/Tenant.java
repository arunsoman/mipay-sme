package com.mipay.sme.domain.core;

import com.mipay.sme.domain.Enums.TenantStatus;
import java.time.Instant;
import com.mipay.sme.domain.Enums.TenantType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import jakarta.persistence.PrePersist;

@Entity
@Table(name = "tenants")
@Getter @Setter
public class Tenant {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private TenantType type;

    @Enumerated(EnumType.STRING)
    private TenantStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
