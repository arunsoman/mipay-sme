package trustplane.rbac.core;

import org.junit.jupiter.api.Test;
import trustplane.rbac.config.RbacConfig;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BridgeDeadlineWatchTest {

    private static RbacConfig config(String environment, Instant deadline) {
        return new RbacConfig("", "", "", "", "", "", "", 120, environment, "", "sme-mipay-mesh", deadline);
    }

    @Test
    void warnsInTheLastTwoWeeksAndOnceClosed() {
        Instant until = Instant.parse("2026-12-31T23:59:59Z");
        assertNull(BridgeDeadlineWatch.status(config("sandbox", until), Instant.parse("2026-11-12T00:00:00Z")));
        String soon = BridgeDeadlineWatch.status(config("sandbox", until), Instant.parse("2026-12-25T00:00:00Z"));
        assertNotNull(soon);
        assertFalse(soon.startsWith("CLOSED"));
        assertTrue(BridgeDeadlineWatch.status(config("sandbox", until), Instant.parse("2027-01-01T00:00:00Z")).startsWith("CLOSED"));
        assertNull(BridgeDeadlineWatch.status(config("dev", null), Instant.now()), "dev without a deadline stays quiet");
        assertTrue(BridgeDeadlineWatch.status(config("production", null), Instant.now()).startsWith("CLOSED"));
    }
}
