package trustplane.rbac.core;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import trustplane.rbac.config.RbacConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * Warns ahead of the temporary core_authorized bridge closing (RBAC_CORE_AUTHORIZED_UNTIL).
 * When it closes, portal writes the core vouches for are refused (403
 * core_authorization_expired) until the core runs with app.rbac.mode=enforce. System sources
 * (go-live, onboarding, lifecycle) are not affected. Logs hourly in the last 14 days, and
 * at error level once closed, so the cut-over is never discovered by a failing screen.
 */
@Component
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class BridgeDeadlineWatch {

    static final Duration WARN_AHEAD = Duration.ofDays(14);

    private final RbacConfig cfg;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 20_000)
    public void check() {
        String msg = status(cfg, Instant.now());
        if (msg == null) return;
        if (msg.startsWith("CLOSED")) log.error("[rbac] {}", msg); else log.warn("[rbac] {}", msg);
    }

    /** null when nothing needs saying. */
    static String status(RbacConfig cfg, Instant now) {
        Instant until = cfg.coreAuthorizedUntil();
        if (until == null) {
            return cfg.environment().equals("dev") ? null
                    : "CLOSED: core_authorized bridge has no deadline outside dev, so it is off; the core must run app.rbac.mode=enforce";
        }
        if (!now.isBefore(until)) return "CLOSED: core_authorized bridge expired at " + until + "; the core must run app.rbac.mode=enforce";
        Duration left = Duration.between(now, until);
        if (left.compareTo(WARN_AHEAD) <= 0) {
            return "core_authorized bridge closes at " + until + " (" + left.toHours() + " h left): cut the core over to app.rbac.mode=enforce";
        }
        return null;
    }
}
