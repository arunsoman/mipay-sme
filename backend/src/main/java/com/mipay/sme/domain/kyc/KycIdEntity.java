package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** String-id base for KYC master and workflow rows. */
@Getter
@Setter
@MappedSuperclass
public abstract class KycIdEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
