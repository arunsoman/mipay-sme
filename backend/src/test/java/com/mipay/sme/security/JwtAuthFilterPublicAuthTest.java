package com.mipay.sme.security;

import com.mipay.sme.config.AppProperties;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.service.OrgAccess;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class JwtAuthFilterPublicAuthTest {
    @Test
    void activationIgnoresExistingInstallerBearer() throws Exception {
        JwtService jwt = mock(JwtService.class);
        AppProperties props = new AppProperties();
        props.setIdentityMode("legacy");
        JwtAuthFilter filter = new JwtAuthFilter(jwt, mock(DeviceSessionRepository.class),
                mock(UserRepository.class), mock(OrganizationRepository.class), mock(OrgAccess.class), props);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/activate");
        request.addHeader("Authorization", "Bearer existing-installer-session");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
        verifyNoInteractions(jwt);
    }
}
