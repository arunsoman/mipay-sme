package com.mipay.sme.ui;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Deploy-time registration of a portal build's widgets (tools/widgets.ts register). Internal
 * network only - like /internal/auth/events it is not under /api and never routed by the
 * gateway's browser lane. It authenticates itself with a shared deploy token
 * (app.ui-registry.token); with no token configured, registration is switched off (404).
 */
@RestController
@RequiredArgsConstructor
public class WidgetRegistryInternalController {

    private final WidgetRegistryService registry;

    @Value("${app.ui-registry.token:}")
    private String token;

    @PostMapping(value = "/internal/ui/widgets/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> register(@RequestHeader(value = "X-Registry-Token", required = false) String presented,
                                           @RequestBody WidgetRegistryService.RegisterRequest body) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "registration_disabled"));
        }
        if (presented == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_token"));
        }
        try {
            return ResponseEntity.ok(registry.register(body));
        } catch (WidgetRegistryService.RejectedException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "rejected", "problems", e.problems()));
        }
    }
}
