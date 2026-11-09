package com.mipay.sme.domain.ledger;

import com.mipay.sme.domain.Enums.AuthType;
import com.mipay.sme.domain.Enums.ConnectorStatus;
import com.mipay.sme.domain.Enums.ConnectorType;
import com.mipay.sme.domain.Enums.PaymentEnvironment;
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

/** Bank/issuer, lender and rail connector configuration (E01-E07, BC p.29). */
@Entity
@Table(name = "integration_connectors")
@Getter @Setter
public class Connector {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "connector_type", nullable = false)
    private ConnectorType connectorType;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentEnvironment environment;

    @Column(name = "base_url")
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type")
    private AuthType authType;

    /** Vault reference only - never plaintext (C.1 integration_connectors note). */
    @Column(name = "credentials_ref")
    private String credentialsRef;

    /** JSON string of capability flags, e.g. {"book_transfer":true,"native_ipp_b2b":false}. */
    @Column(columnDefinition = "varchar(2000)")
    private String capabilities;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConnectorStatus status;

    @Column(name = "last_health_check")
    private Instant lastHealthCheck;

    @Column(name = "health_latency_ms")
    private Integer healthLatencyMs;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
