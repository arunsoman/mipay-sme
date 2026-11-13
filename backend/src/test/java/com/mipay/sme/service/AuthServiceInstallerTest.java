package com.mipay.sme.service;

import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.repository.*;
import com.mipay.sme.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AuthServiceInstallerTest {
    @Test
    void installerLoginNeverCreatesACoreCredential() {
        UserRepository users = mock(UserRepository.class);
        when(users.findAllByIdentifier("installer")).thenReturn(List.of());
        AuthService auth = new AuthService(users, mock(DeviceSessionRepository.class),
                mock(MerchantRepository.class), mock(SupplierRepository.class), mock(LenderRepository.class),
                mock(OrganizationRepository.class), mock(JwtService.class), mock(OrgAccess.class),
                mock(PasswordEncoder.class), mock(AppProperties.class));

        assertThrows(ForbiddenException.class, () -> auth.loginWithPassword("installer", "any-password"));
        verify(users, never()).save(any());
    }
}
