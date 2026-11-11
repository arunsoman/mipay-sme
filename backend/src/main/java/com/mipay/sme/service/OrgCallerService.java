package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Caller resolution shared by the tree, suspension and onboarding services:
 * platform authority comes from the role string ([H2]), the org binding from
 * the user row (JWT-derived principal only).
 */
@Service
@RequiredArgsConstructor
public class OrgCallerService {

    private final UserRepository userRepo;
    private final OrganizationRepository orgRepo;

    public boolean platformStaff(Current.Principal caller) {
        String role = caller.role();
        return Enums.UserRole.TPTS_OPS.name().equals(role)
                || Enums.UserRole.MINDLABS_ADMIN.name().equals(role);
    }

    public Organization orgOf(Current.Principal caller) {
        User u = userRepo.findById(caller.userId()).orElse(null);
        if (u == null || u.getOrgId() == null) return null;
        return orgRepo.findById(u.getOrgId()).orElse(null);
    }
}
