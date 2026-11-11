package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Choice list header; the key is the code itself (§3.4). */
@Getter
@Setter
@Entity
@Table(name = "kyc_lists")
public class KycList {

    @Id
    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "description", length = 500)
    private String description;

    public KycList() { }

    public KycList(String code, String country, String description) {
        this.code = code;
        this.country = country;
        this.description = description;
    }
}
