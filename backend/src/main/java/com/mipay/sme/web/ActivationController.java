package com.mipay.sme.web;

import com.mipay.sme.security.Current;
import com.mipay.sme.service.ActivationService;
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

    /** Resend (new invalidating token) — org admin or platform staff only. */
    @PostMapping("/api/orgs/users/{id}/resend-activation")
    public Dtos.Message resend(@PathVariable String id) {
        String outboxId = activation.resend(id, Current.get());
        return new Dtos.Message("Activation re-issued; outbox entry " + outboxId, null);
    }

    /** Sandbox-only outbox ([M8]) — never in production environments. */
    @GetMapping("/api/admin/sim/outbox")
    public List<Dtos.OutboxRow> outbox() {
        return activation.outbox(Current.get());
    }

    public record CompleteActivationRequest(String token, String password) { }
}
