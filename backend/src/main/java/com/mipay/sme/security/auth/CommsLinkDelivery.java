package com.mipay.sme.security.auth;

import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Conditional;

/**
 * Delivers one-time set-up and reset links through the comms plane, using the platform's
 * own event codes (account.setup for enrolment, auth.password.reset for resets). Contacts
 * are (re-)registered from the core's user row at send time — the core is the only contact
 * writer and row state wins; a comms outage fails the outbox row, which retries.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Conditional(CommsLinkDelivery.CommsConfigured.class)
public class CommsLinkDelivery implements LinkDelivery {
    private final CommsClient comms;
    private final UserRepository users;

    /** Only wired when the comms plane is configured — otherwise tests and legacy mode run without it. */
    static class CommsConfigured implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext ctx,
                               org.springframework.core.type.AnnotatedTypeMetadata md) {
            String url = ctx.getEnvironment().getProperty("app.comms-url");
            return url != null && !url.isBlank();
        }
    }

    @Override
    public void deliver(Request request) {
        User user = users.findById(request.userId())
                .orElseThrow(() -> new IllegalStateException("link delivery for unknown user " + request.userId()));
        comms.putContacts(request.userId(), user.getPhone(), user.getEmail());
        boolean enrol = "ENROL".equals(request.kind());
        String eventCode = enrol ? CommsClient.EVENT_SETUP_LINK : CommsClient.EVENT_RESET_LINK;
        comms.sendLink(eventCode, request.userId(), request.subject(), request.oneTimeLink(),
                user.getName(), request.expiresAt());
        log.debug("delivered {} link to user {} through the comms plane", request.kind(), request.userId());
    }
}
