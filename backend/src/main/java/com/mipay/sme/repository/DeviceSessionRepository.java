package com.mipay.sme.repository;

import com.mipay.sme.domain.core.DeviceSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceSessionRepository extends JpaRepository<DeviceSession, String> {

    List<DeviceSession> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<DeviceSession> findByUserId(String userId);

    /** Device lists cover ALL users of the org (H3/M9), newest first. */
    List<DeviceSession> findByUserIdInOrderByCreatedAtDesc(List<String> userIds, Pageable pageable);

    List<DeviceSession> findByUserIdIn(List<String> userIds);

    /** jti revocation: the live token dies the moment this returns false (TC-SEC-04). */
    boolean existsByTokenHashAndExpiresAtAfter(String tokenHash, java.time.Instant now);

    long countByUserIdAndExpiresAtAfter(String userId, java.time.Instant now);

    long deleteByUserId(String userId);
}
