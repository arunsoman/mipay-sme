package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.DeviceSession;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.security.JwtService;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auth for the demo/UAT build:
 * - merchants log in with phone + OTP (fixed 123456 in sandbox, SIMULATED-labelled),
 * - ops/lender/admin tenants log in with phone + password (seeded {noop} for demo).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final com.mipay.sme.repository.UserRepository userRepo;
    private final DeviceSessionRepository sessionRepo;
    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final LenderRepository lenderRepo;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final com.mipay.sme.config.AppProperties props;

    private final Map<String, String> otpStore = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public Dtos.OtpResult requestOtp(String phone) {
        User user = userByPhone(phone);
        if (user.getRole() != Enums.UserRole.MERCHANT) {
            throw new ForbiddenException("Use password login for role " + user.getRole());
        }
        if (user.getStatus() == Enums.UserStatus.SUSPENDED) {
            throw new ForbiddenException("Account suspended");
        }
        String otp = props.isSimulatedEnvironment() ? props.getDevOtp()
                : String.format("%06d", random.nextInt(1_000_000));
        otpStore.put(phone, otp);
        if (!props.isSimulatedEnvironment()) {
            log.info("OTP issued for {} (production path: deliver via SMS gateway)", phone);
        }
        String hint = props.isSimulatedEnvironment() ? otp : null;
        return new Dtos.OtpResult(phone, hint, props.isSimulatedEnvironment());
    }

    @Transactional
    public Dtos.TokenView verifyOtp(String phone, String code) {
        User user = userByPhone(phone);
        String expected = otpStore.remove(phone);
        if (expected == null || !expected.equals(code)) {
            throw new ForbiddenException("Invalid or expired OTP");
        }
        assertLoginAllowed(user);
        return issueToken(user);
    }

    /** Platform-level block: suspended merchant/supplier cannot log in (TC-OPS-11). */
    private void assertLoginAllowed(User user) {
        if (user.getStatus() == Enums.UserStatus.SUSPENDED) {
            throw new ForbiddenException("Account suspended");
        }
        if (user.getRole() == Enums.UserRole.MERCHANT) {
            merchantRepo.findByUserId(user.getId()).ifPresent(m -> {
                if (m.getApprovalStatus() != Enums.ApprovalStatus.APPROVED) {
                    throw new ForbiddenException("Merchant is " + m.getApprovalStatus() + " - login blocked");
                }
            });
        }
        if (user.getRole() == Enums.UserRole.SUPPLIER) {
            supplierRepo.findByUserId(user.getId()).ifPresent(sp -> {
                if (sp.getApprovalStatus() != Enums.ApprovalStatus.APPROVED) {
                    throw new ForbiddenException("Supplier is " + sp.getApprovalStatus() + " - login blocked");
                }
            });
        }
    }

    @Transactional
    public Dtos.TokenView loginWithPassword(String phone, String password) {
        User user = userByPhone(phone);
        if (user.getRole() == Enums.UserRole.MERCHANT) {
            throw new ForbiddenException("Merchants log in with phone + OTP");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ForbiddenException("Invalid credentials");
        }
        assertLoginAllowed(user);
        return issueToken(user);
    }

    private Dtos.TokenView issueToken(User user) {
        String merchantId = merchantRepo.findByUserId(user.getId()).map(m -> m.getId()).orElse(null);
        String supplierId = supplierRepo.findByUserId(user.getId()).map(s -> s.getId()).orElse(null);
        String lenderId = lenderRepo.findByUserId(user.getId()).map(l -> l.getId()).orElse(null);

        JwtService.IssuedToken issued = jwtService.issue(user, merchantId, supplierId, lenderId);

        DeviceSession session = new DeviceSession();
        session.setUserId(user.getId());
        session.setDeviceFingerprint("web");
        // only the SHA-256 of the jti is stored - a DB dump never yields live credentials (TC-SEC-02)
        session.setTokenHash(com.mipay.sme.common.HashUtil.sha256(issued.jti()));
        session.setMfaMethod(user.getRole() == Enums.UserRole.MERCHANT ? "OTP" : "PASSWORD");
        session.setExpiresAt(issued.expiresAt());
        sessionRepo.save(session);

        return new Dtos.TokenView(issued.token(), "Bearer", JwtService.TTL.toSeconds(), user.getRole().name());
    }

    private User userByPhone(String phone) {
        return userRepo.findByPhone(phone).orElseThrow(() -> new ConflictException("No user for phone " + phone));
    }
}
