package com.mipay.sme.service.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit one-time deployment hook for moving existing users to the IdP.
 * Runs only when APP_MIGRATION_IDENTITY_BACKFILL_ONCE=true and the trustplane
 * credential provider is enabled. The existing backfill is idempotent; this
 * hook logs aggregate outcomes and never logs usernames or setup links.
 */
@Component
@ConditionalOnProperty(name = "app.migration.identity-backfill-once", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public final class IdentityBackfillOnce implements ApplicationRunner {
    private final IdentityBackfill backfill;

    @Override
    public void run(ApplicationArguments args) {
        IdentityBackfill.Report report = backfill.run();
        long failed = report.rows().stream().filter(row -> "FAILED".equals(row.resolution())).count();
        long active = report.rows().stream().filter(row -> "ACTIVE".equals(row.linkStatus())).count();
        long pending = report.rows().stream().filter(row -> row.resolution().startsWith("SETUP_LINK_")).count();
        log.info("[identity-backfill-once] scanned={} active={} pending_setup={} failed={}",
                report.scanned(), active, pending, failed);
        if (report.errors() || failed > 0) {
            log.error("[identity-backfill-once] incomplete; gateway identity must remain disabled");
        }
    }
}
