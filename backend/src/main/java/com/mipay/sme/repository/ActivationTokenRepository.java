package com.mipay.sme.repository;

import com.mipay.sme.domain.org.ActivationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ActivationTokenRepository extends JpaRepository<ActivationToken, String> {

    Optional<ActivationToken> findByTokenHash(String tokenHash);

    /** Single-use consumption as one atomic UPDATE (activation spec). */
    @Modifying
    @Query("""
            update ActivationToken t set t.usedAt = :now
            where t.tokenHash = :hash and t.usedAt is null and t.expiresAt > :now
            """)
    int consume(@Param("hash") String hash, @Param("now") Instant now);

    /** Invalidate every outstanding token of a user before issuing a new one. */
    @Modifying
    @Query("""
            update ActivationToken t set t.usedAt = :now
            where t.userId = :userId and t.usedAt is null
            """)
    void invalidateAllForUser(@Param("userId") String userId, @Param("now") Instant now);
}
