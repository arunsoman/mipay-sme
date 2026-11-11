package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** HMAC blind index over SECRET canonical values (§8): never stores plaintext. */
@Getter
@Setter
@Entity
@Table(name = "kyc_search_index")
@IdClass(KycSearchIndexRow.PK.class)
@NoArgsConstructor
public class KycSearchIndexRow {

    @jakarta.persistence.Id
    @Column(name = "application_id", nullable = false, length = 36)
    private String applicationId;

    @jakarta.persistence.Id
    @Column(name = "canonical_tag", nullable = false, length = 64)
    private String canonicalTag;

    @jakarta.persistence.Id
    @Column(name = "hmac", nullable = false, length = 64)
    private String hmac;

    public KycSearchIndexRow(String applicationId, String canonicalTag, String hmac) {
        this.applicationId = applicationId;
        this.canonicalTag = canonicalTag;
        this.hmac = hmac;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class PK implements java.io.Serializable {
        private String applicationId;
        private String canonicalTag;
        private String hmac;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return applicationId.equals(pk.applicationId) && canonicalTag.equals(pk.canonicalTag)
                    && hmac.equals(pk.hmac);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * applicationId.hashCode() + canonicalTag.hashCode()) + hmac.hashCode();
        }
    }
}
