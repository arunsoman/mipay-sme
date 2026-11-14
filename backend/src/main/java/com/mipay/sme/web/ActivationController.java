package com.mipay.sme.web;

import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.ActivationService;
import com.mipay.sme.service.IdpSetupLinkService;
import com.mipay.sme.config.AppProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Activation mechanics ([C4/H8/L2/M8]): public completion endpoint (token in
 * the body, never via any authenticated API), resend for sanctioned callers,
 * and the sandbox-only simulated outbox for MINDLABS_ADMIN.
 */
@RestController
@RequiredArgsConstructor
public class ActivationController {

    private final ActivationService activation;
    private final IdpSetupLinkService idpLinks;
    private final AppProperties properties;

    /** Resend (new invalidating token) — org admin or platform staff only. */
    @Perm(resource = "org.user", verb = Verb.ADMINISTER, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Issue a set-up link for a user in the manager's org subtree")
    @PostMapping("/api/orgs/users/{id}/resend-activation")
    public ResponseEntity<?> resend(@PathVariable String id) {
        if ("gateway".equals(properties.getIdentityMode())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(idpLinks.issue(id, Current.get()));
        }
        String tokenId = activation.resend(id, Current.get());
        // the set-up link itself (also emailed through the comms plane): User management shows it to copy
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Dtos.Message("A new set-up link was issued", activation.linkOfToken(tokenId)));
    }

    /** Sandbox-only outbox ([M8]) — never in production environments. */
    @Perm(resource = "admin.platform_ops", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View simulated activation outbox")
    @GetMapping("/api/admin/sim/outbox")
    public List<Dtos.OutboxRow> outbox() {
        return activation.outbox(Current.get());
    }

    public record CompleteActivationRequest(String token, String password) { }
}
