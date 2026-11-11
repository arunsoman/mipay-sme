package com.mipay.sme.security.rbac;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An intentionally public endpoint (no login): login/OTP/activation, public payment pages,
 * self-registration, provider webhooks. Registered with RBAC as public so it appears in the
 * inventory and can be checked against the approved public list.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {
    /** Why this endpoint is public - required, reviewed. */
    String reason();
}
