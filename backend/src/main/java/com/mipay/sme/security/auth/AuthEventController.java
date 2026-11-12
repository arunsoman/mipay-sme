package com.mipay.sme.security.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives IdP auth events: POST IDP_CORE_EVENTS_URL, Content-Type application/jwt, a signed
 * JWS (typ auth-event+jwt) verified by {@link IdpEventVerifier}. The endpoint self-authenticates
 * — only the IdP can sign with its ES256 key — and applies each event exactly once.
 */
@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
public class AuthEventController {
    private final IdpEventVerifier verifier;
    private final AuthEventIngestService ingest;

    @PostMapping(value = "/events", consumes = "application/jwt")
    public ResponseEntity<Object> receive(@RequestBody String compactJws) {
        try {
            ingest.apply(verifier.verify(compactJws));
            return ResponseEntity.ok().build();
        } catch (IdpEventVerifier.RejectedException e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "invalid_event", "error_description", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "apply_failed", "error_description", String.valueOf(e.getMessage())));
        }
    }
}
