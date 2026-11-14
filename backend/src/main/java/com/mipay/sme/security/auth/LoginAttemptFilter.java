package com.mipay.sme.security.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in‑memory login‑attempt counter for FAPI‑compliant lockout.
 * Configurable properties (default values shown):
 *   - app.login.attemptLimit     – max failed attempts before lockout (default 5)
 *   - app.login.windowMinutes    – time window in minutes for counting attempts (default 15)
 *   - app.login.lockoutMinutes   – lockout duration in minutes (default 30)
 * - Tracks failures per IP address.
 * - After `limit` failed attempts within `windowMinutes` the IP is blocked for `lockoutMinutes`.
 * - The block is lifted after the timeout; subsequent attempts reset the counter.
 * - This filter runs before the authentication entry point so that the 401 response
 *   can carry a FAPI‑style `WWW-Authenticate` header if desired.
 */
@Component
@Order(1)
public class LoginAttemptFilter extends OncePerRequestFilter {

    @Value("${app.login.attemptLimit:5}")
    private int attemptLimit;

    @Value("${app.login.windowMinutes:15}")
    private int windowMinutes;

    @Value("${app.login.lockoutMinutes:30}")
    private int lockoutMinutes;

    // In‑memory storage: key = "ip:windowStartEpoch", value = count
    private final Map<String, Integer> attemptMap = new ConcurrentHashMap<>();
    private final Map<String, Long> lockoutUntil = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        // Only enforce on the login endpoint; other routes pass through
        return !request.getRequestURI().startsWith("/api/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();

        // If the IP is currently locked, immediately reject
        String ip = request.getRemoteAddr();
        Long lockExpiry = lockoutUntil.get(ip);
        if (lockExpiry != null && Instant.now().getEpochSecond() < lockExpiry) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            try {
                response.getWriter().write(
                        "{\"error\":\"account_locked\",\"error_description\":\"too many failed logins, try again later\"}");
            } catch (IOException ignored) {}
            return;
        }

        // Reset lockout if the time has passed
        if (lockExpiry != null && Instant.now().getEpochSecond() >= lockExpiry) {
            lockoutUntil.remove(ip);
            attemptMap.clear(); // start fresh for this IP
        }

        if ("POST".equals(request.getMethod()) && uri.endsWith("/auth/login")) {
            // Count this attempt (the actual authentication is performed later;
            // we just record that a login POST was seen)
            String key = ip + ":" + (Instant.now().getEpochSecond() / (windowMinutes * 60));
            attemptMap.merge(key, 1, Integer::sum);

            // Check if we have exceeded the limit
            int attempts = attemptMap.get(key);
            if (attempts >= attemptLimit) {
                // Lock out for lockoutMinutes
                lockoutUntil.put(ip, Instant.now().getEpochSecond() + (long) lockoutMinutes * 60);
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(lockoutMinutes));
                try {
                    response.getWriter().write(
                            "{\"error\":\"account_locked\",\"error_description\":\"too many failed logins, try again in " + lockoutMinutes + " minutes\"}");
                } catch (IOException ignored) {}
                // optionally clear the attempt counter for this IP to avoid spam
                attemptMap.remove(key);
                return;
            }
        }

        chain.doFilter(request, response);
    }
}