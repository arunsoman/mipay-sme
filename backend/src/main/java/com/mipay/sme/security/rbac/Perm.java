package com.mipay.sme.security.rbac;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a controller method to ONE cell of the permission grid: {@code resource} is the row,
 * {@code verb} the column. Declared once, enforced twice (design Appendix F/G):
 * <ul>
 *   <li>Spring Security - this is a meta-annotation for {@code @PreAuthorize}, evaluated by
 *       {@link RbacAuthz} before the method body runs;</li>
 *   <li>RBAC / gateway - {@link EndpointManifestPublisher} discovers every {@code @Perm} at startup
 *       and registers the permission catalogue with trustplane RBAC, so the gateway can enforce the
 *       same cell before routing.</li>
 * </ul>
 * Every handler must carry {@code @Perm} or {@link PublicEndpoint} (startup audit + unit test).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@rbacAuthz.check('{resource}', '{verb}', '{tenantSource}', {tenant})")
public @interface Perm {

    /** The row: {@code <service>.<resource>}, e.g. {@code kyc.application}. */
    String resource();

    /** The column. */
    Verb verb();

    /** Where the org comes from. */
    TenantSource tenantSource() default TenantSource.NONE;

    /** SpEL for the org id when it is known at the controller (e.g. {@code #id}); {@code null} otherwise. */
    String tenant() default "null";

    /** For {@link TenantSource#LOOKUP}: the resolver id the gateway / service uses, e.g. {@code kyc_task_org}. */
    String lookup() default "";

    /** Shown in the RBAC catalogue and the Roles & access screen. */
    String description() default "";
}
