package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Inbound provider webhook; delivered at most once on (provider, event_id) (§7). */
@Getter
@Setter
@Entity
@Table(name = "kyc_provider_events")
public class KycProviderEvent extends KycIdEntity {

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed", nullable = false)
    private boolean processed;
}
