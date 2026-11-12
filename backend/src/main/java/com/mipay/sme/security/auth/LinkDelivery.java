package com.mipay.sme.security.auth;

import java.time.Instant;

/** Hands a one-time enrolment or reset link to the comms plane; the link is never persisted by the core. */
public interface LinkDelivery {
    record Request(String userId, String subject, String kind, String oneTimeLink, Instant expiresAt) { }

    void deliver(Request request);
}
