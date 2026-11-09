package com.mipay.sme.security;

import com.mipay.sme.common.HashUtil;
import com.mipay.sme.domain.core.DeviceSession;
import com.mipay.sme.repository.DeviceSessionRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer-token filter. Validates the signature AND checks the jti against
 * devices_sessions so a revoked/compromised token stops working instantly
 * (TC-SEC-04). Revocation is performed by MINDLABS_ADMIN on /api/admin/access.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final DeviceSessionRepository sessionRepo;

    @Override
    @SuppressWarnings("NullableProblems")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwtService.parse(header.substring(7));
                // jti revocation: a live JWT dies the moment its session is deleted
                if (!sessionActive(c.getId())) {
                    log.debug("Rejected revoked/expired session token for subject {}", c.getSubject());
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                Current.Principal principal = new Current.Principal(
                        c.getSubject(),
                        c.get("role", String.class),
                        c.get("name", String.class),
                        c.get("phone", String.class),
                        c.get("merchantId", String.class),
                        c.get("supplierId", String.class),
                        c.get("lenderId", String.class));
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException e) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private boolean sessionActive(String jti) {
        if (jti == null || jti.isBlank()) {
            return false; // tokens issued before jti adoption are refused - re-login required
        }
        return sessionRepo.existsByTokenHashAndExpiresAtAfter(
                HashUtil.sha256(jti), java.time.Instant.now());
    }
}
