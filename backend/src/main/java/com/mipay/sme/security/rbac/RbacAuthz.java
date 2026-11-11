package com.mipay.sme.security.rbac;

import com.mipay.sme.security.Current;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The method-security decision behind {@link Perm} ({@code @rbacAuthz.check(...)}).
 *
 * Modes ({@code app.rbac.mode}):
 * <ul>
 *   <li>{@code shadow} (default) - never blocks; logs the cell each call would need, so the
 *       catalogue and the future role grids can be checked against real traffic;</li>
 *   <li>{@code off} - never blocks, no logging;</li>
 *   <li>{@code enforce} - not available until the role grids exist in RBAC (migration step 4b);
 *       refusing it at startup avoids silently allowing or denying everything.</li>
 * </ul>
 */
@Component("rbacAuthz")
@Slf4j
public class RbacAuthz {

    @Value("${app.rbac.mode:shadow}")
    private String mode;

    @PostConstruct
    void validateMode() {
        if (!mode.equals("shadow") && !mode.equals("off")) {
            throw new IllegalStateException("app.rbac.mode=" + mode + " is not available yet "
                    + "(enforce needs the RBAC role grids - design Appendix G, rollout step 5)");
        }
        log.info("[rbac] method security in {} mode", mode);
    }

    public boolean check(String resource, String verb, String tenantSource, Object tenant) {
        if (mode.equals("shadow") && log.isDebugEnabled()) {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            String sub = auth != null && auth.getPrincipal() instanceof Current.Principal p ? p.userId() : "-";
            log.debug("[rbac-shadow] sub={} perm={}:{} tenantSource={} tenant={}",
                    sub, resource, verb.toLowerCase(), tenantSource, tenant);
        }
        return true;
    }
}
