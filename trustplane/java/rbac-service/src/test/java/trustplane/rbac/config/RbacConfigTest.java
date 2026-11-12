package trustplane.rbac.config;

import org.junit.jupiter.api.Test;
import trustplane.rbac.web.WebSupport;
import trustplane.shared.ApiException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RbacConfigTest {

    private static RbacConfig config(String environment, Instant deadline) {
        return new RbacConfig("", "", "", "", "", "", "", "", 120,
                environment, "", "sme-mipay-mesh", deadline);
    }

    @Test
    void compatibilityBridgeDefaultsOpenOnlyInDevelopment() {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        assertTrue(config("dev", null).coreAuthorizedBridgeOpen(now));
        assertFalse(config("sandbox", null).coreAuthorizedBridgeOpen(now));
        assertFalse(config("production", null).coreAuthorizedBridgeOpen(now));
    }

    @Test
    void compatibilityBridgeClosesAtItsDeadline() {
        Instant deadline = Instant.parse("2026-12-31T23:59:59Z");
        RbacConfig cfg = config("sandbox", deadline);
        assertTrue(cfg.coreAuthorizedBridgeOpen(deadline.minusSeconds(1)));
        assertFalse(cfg.coreAuthorizedBridgeOpen(deadline));
        assertFalse(cfg.coreAuthorizedBridgeOpen(deadline.plusSeconds(1)));
    }

    @Test
    void expiredCoreAssertionIsRejectedButAnAbsentAssertionFallsBackToRbac() {
        RbacConfig cfg = config("sandbox", Instant.parse("2026-11-11T00:00:00Z"));
        assertThrows(ApiException.class, () -> WebSupport.coreAuthorized(true, cfg));
        assertFalse(WebSupport.coreAuthorized(false, cfg));
    }
}
