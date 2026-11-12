package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.security.Current;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayIdentityFilterTest {
    @TempDir Path temp;

    @Test void acceptsOnlyFreshSignedContextResolvedByCore() throws Exception {
        byte[] key = "01234567890123456789012345678901\n".getBytes(StandardCharsets.UTF_8);
        Path file = temp.resolve("ctx-secret");
        Files.write(file, key);
        AppProperties props = new AppProperties();
        props.setIdentityMode("gateway");
        props.setGatewayContextSecretFile(file.toString());
        IdentityResolver resolver = mock(IdentityResolver.class);
        when(resolver.resolve(eq("https://idp.test"), eq("sub_1"), eq("u1"), eq("aal2"),
                eq(List.of("pwd", "totp")), anyLong(), eq("sid_1"), eq("bff"), eq("GET"), eq("/api/me")))
                .thenReturn(new Current.Principal("u1", "MERCHANT", "Alice", "+264810000042",
                        "merchant_1", null, null, "org_1", "MERCHANT", "ADMIN"));
        ObjectMapper mapper = new ObjectMapper();
        GatewayIdentityFilter filter = new GatewayIdentityFilter(props, resolver, mapper);
        String payload = mapper.writeValueAsString(Map.of(
                "iss", "https://idp.test", "sub", "sub_1", "uid", "u1", "acr", "aal2",
                "amr", List.of("pwd", "totp"), "auth_time", Instant.now().getEpochSecond(),
                "sid", "sid_1", "client_id", "bff", "ts", Instant.now().getEpochSecond()));
        String header = signed(key, payload);
        AtomicBoolean reached = new AtomicBoolean();
        FilterChain chain = (request, response) -> {
            assertEquals("u1", Current.get().userId());
            reached.set(true);
        };
        var valid = request(header);
        var ok = new MockHttpServletResponse();
        filter.doFilter(valid, ok, chain);
        assertTrue(reached.get());
        assertEquals(200, ok.getStatus());

        reached.set(false);
        var forged = new MockHttpServletResponse();
        filter.doFilter(request(header.substring(0, header.length() - 2) + "xx"), forged, chain);
        assertEquals(401, forged.getStatus());
        assertFalse(reached.get());

        String old = mapper.writeValueAsString(Map.of("iss", "https://idp.test", "sub", "sub_1",
                "uid", "u1", "ts", Instant.now().minusSeconds(60).getEpochSecond()));
        var stale = new MockHttpServletResponse();
        filter.doFilter(request(signed(key, old)), stale, chain);
        assertEquals(401, stale.getStatus());
        verify(resolver, times(1)).resolve(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    private static MockHttpServletRequest request(String header) {
        var request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("X-SME-Context", header);
        return request;
    }

    private static String signed(byte[] key, String payload) throws Exception {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
    }
}
