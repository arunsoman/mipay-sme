package com.mipay.sme.security.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
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
 * - After `limit` FAILED attempts within `windowMinutes` the IP is blocked for `lockoutMinutes`.
 * - The block is lifted after the timeout; subsequent attempts reset the counter.
 * - This filter runs before the authentication entry point so that the 401 response
 *   can carry a FAPI‑style `WWW-Authenticate` header if desired.
 *
 * Bug #5 fix: the counter is incremented AFTER the filter chain executes and only
 * when the downstream response signals an authentication failure (4xx). Previously
 * the counter was incremented on every login request regardless of outcome, which
 * caused five successful logins to trigger a lockout.
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

        String ip = request.getRemoteAddr();

        // Clear expired lockout before checking so the check below is fresh.
        Long lockExpiry = lockoutUntil.get(ip);
        if (lockExpiry != null && Instant.now().getEpochSecond() >= lockExpiry) {
            lockoutUntil.remove(ip);
            attemptMap.entrySet().removeIf(e -> e.getKey().startsWith(ip + ":"));
            lockExpiry = null;
        }

        // If the IP is currently locked, immediately reject.
        if (lockExpiry != null) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(lockoutMinutes * 60));
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            try {
                response.getWriter().write(
                        "{\"error\":\"account_locked\",\"error_description\":\"too many failed logins, try again later\"}");
            } catch (IOException ignored) {}
            return;
        }

        // Let the request proceed through the full filter chain first.
        chain.doFilter(request, response);

        // --- POST-authentication: only count failures (Bug #5 fix) ---
        // A successful authentication returns 2xx or 3xx; 4xx means the
        // credentials were wrong or the IdP rejected the attempt.
        if ("POST".equals(request.getMethod())
                && request.getRequestURI().contains("/auth/login")
                && response.getStatus() >= 400) {

            String key = ip + ":" + (Instant.now().getEpochSecond() / (windowMinutes * 60L));
            int attempts = attemptMap.merge(key, 1, Integer::sum);

            if (attempts >= attemptLimit) {
                lockoutUntil.put(ip, Instant.now().getEpochSecond() + (long) lockoutMinutes * 60);
                attemptMap.remove(key);
                // The response has already been written by the downstream filter;
                // override the status code to 429 so the client knows it is locked out.
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(lockoutMinutes * 60));
            }
        }
    }
}
