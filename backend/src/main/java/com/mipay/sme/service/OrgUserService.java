package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Org-scoped user reads + session kill used by deactivation and archiving ([M9]/[H9]). */
@Service
@RequiredArgsConstructor
public class OrgUserService {

    private final UserRepository userRepo;
    private final DeviceSessionRepository sessionRepo;

    public List<User> usersOf(String orgId) {
        return userRepo.findByOrgId(orgId);
    }

    public long countActiveAdmins(String orgId) {
        return userRepo.findByOrgIdAndOrgRole(orgId, Enums.OrgMemberRole.ADMIN).stream()
                .filter(u -> u.getStatus() == Enums.UserStatus.ACTIVE)
                .count();
    }

    /** Kill every live session of a user (jti revocation makes deletion instant). */
    public void deleteSessions(String userId) {
        sessionRepo.deleteAll(sessionRepo.findByUserId(userId));
    }
}
