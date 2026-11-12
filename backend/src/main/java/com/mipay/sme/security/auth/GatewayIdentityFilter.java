package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.security.Current;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Verifies the gateway's HMAC and resolves its IdP subject against core state. */
@Component
public final class GatewayIdentityFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-SME-Context";
    private final AppProperties properties;
    private final IdentityResolver resolver;
    private final ObjectMapper mapper;
    private final byte[] key;

    public GatewayIdentityFilter(AppProperties properties, IdentityResolver resolver, ObjectMapper mapper) {
        this.properties = properties;
        this.resolver = resolver;
        this.mapper = mapper;
        if ("gateway".equals(properties.getIdentityMode())) {
            try {
                key = Files.readAllBytes(Path.of(properties.getGatewayContextSecretFile()));
                if (key.length < 32) throw new IllegalStateException("gateway context key is too short");
            } catch (IOException e) {
                throw new IllegalStateException("gateway identity requires its context key file", e);
            }
        } else key = new byte[0];
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"gateway".equals(properties.getIdentityMode())
                || request.getRequestURI().equals("/actuator/health")
                || request.getRequestURI().startsWith("/internal/")) {
            chain.doFilter(request, response);
            return;
        }
        Current.Principal principal;
        try {
            JsonNode context = verify(request.getHeader(HEADER));
            List<String> amr = new ArrayList<>();
            if (context.path("amr").isArray()) context.path("amr").forEach(value -> amr.add(value.asText()));
            principal = resolver.resolve(context.path("iss").asText(""),
                    context.path("sub").asText(""), context.path("uid").asText(""),
                    context.path("acr").asText(""), amr, context.path("auth_time").asLong(0),
                    context.path("sid").asText(""), context.path("client_id").asText(""),
                    request.getMethod(), request.getRequestURI());
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        try {
            var auth = new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private JsonNode verify(String header) throws Exception {
        if (header == null || header.length() > 8192) throw new SecurityException("missing context");
        String[] parts = header.split("\\.", -1);
        if (parts.length != 2) throw new SecurityException("invalid context");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] expected = mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII));
        if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[1])))
            throw new SecurityException("invalid context signature");
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        long ts = payload.path("ts").asLong(0);
        long age = Instant.now().getEpochSecond() - ts;
        if (age > 30 || age < -30) throw new SecurityException("stale context");
        return payload;
    }
}
