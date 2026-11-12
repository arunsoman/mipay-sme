package com.mipay.sme.service.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * With {@code app.rbac.mode=enforce}, RBAC must know everyone the legacy checks let in, or they
 * are locked out. The RBAC backfill is idempotent (an active assignment is SKIPPED), so it runs
 * at every boot, after the org-tree sync has had time to give RBAC the tenants; it retries
 * while RBAC or the tenants are not there yet. Rows it cannot write are logged as errors.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RbacBackfillAtBoot {

    private final RbacBackfill backfill;

    @Value("${app.rbac.mode:shadow}")
    private String mode;

    @EventListener(ApplicationReadyEvent.class)
    public void runWhenEnforcing() {
        if (!"enforce".equals(mode)) return;
        Thread t = new Thread(this::attempts, "rbac-backfill-boot");
        t.setDaemon(true);
        t.start();
    }

    private void attempts() {
        for (int i = 1; i <= 6; i++) {
            try {
                Thread.sleep(i == 1 ? 10_000 : 20_000);
                RbacBackfill.Report r = backfill.run();
                r.rows().stream().filter(x -> "FAILED".equals(x.resolution()))
                        .forEach(x -> log.error("[rbac-backfill] {} {} on {} failed: {}", x.subject(), x.role(), x.tenant(), x.note()));
                log.info("[rbac-backfill] at boot: {} assignments, {} grants written; errors={}",
                        r.assignmentsWritten(), r.grantsWritten(), r.errors());
                if (!r.errors()) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[rbac-backfill] attempt {} failed: {}", i, e.getMessage());
            }
        }
    }
}
