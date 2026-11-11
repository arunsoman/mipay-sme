package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Platform functions: OPS_REVIEWER, COMPLIANCE_OFFICER, MLRO, SENIOR_APPROVER (§6.6). */
@Getter
@Setter
@Entity
@Table(name = "user_platform_functions")
@IdClass(UserPlatformFunction.PK.class)
@NoArgsConstructor
public class UserPlatformFunction {

    public static final String OPS_REVIEWER = "OPS_REVIEWER";
    public static final String COMPLIANCE_OFFICER = "COMPLIANCE_OFFICER";
    public static final String MLRO = "MLRO";
    public static final String SENIOR_APPROVER = "SENIOR_APPROVER";

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Id
    @Column(name = "function", nullable = false, length = 32)
    private String function;

    public UserPlatformFunction(String userId, String function) {
        this.userId = userId;
        this.function = function;
    }

    /** Composite key (user_id, function). */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PK implements java.io.Serializable {
        private String userId;
        private String function;

        public PK(String userId, String function) {
            this.userId = userId;
            this.function = function;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return userId.equals(pk.userId) && function.equals(pk.function);
        }

        @Override
        public int hashCode() {
            return 31 * userId.hashCode() + function.hashCode();
        }
    }
}
