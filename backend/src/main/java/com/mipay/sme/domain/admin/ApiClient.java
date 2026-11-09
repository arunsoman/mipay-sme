package com.mipay.sme.domain.admin;

import com.mipay.sme.domain.Enums.ApiClientStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** API client keys with scopes and rate limits (X10, BC p.29). */
@Entity
@Table(name = "api_clients")
@Getter @Setter
public class ApiClient {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "client_name", nullable = false)
    private String clientName;

    @Column(name = "client_id", unique = true, nullable = false)
    private String clientId;

    /** JSON array of scopes, e.g. ["ledger:read","payments:write"]. */
    @Column(columnDefinition = "varchar(1000)")
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApiClientStatus status = ApiClientStatus.ACTIVE;

    @Column(name = "rate_limit_tps")
    private Integer rateLimitTps = 20;

    @Column(name = "last_rotated_at")
    private Instant lastRotatedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
