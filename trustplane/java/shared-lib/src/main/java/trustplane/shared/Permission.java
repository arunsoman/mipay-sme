package trustplane.shared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an endpoint method to a concrete permission id
 * (e.g. "rbac.role:approve"). The security contract is:
 *
 *   resource action        &lt;-&gt;  {@code @Permission("rbac.role:approve")}
 *                                   + {@code @PreAuthorize("hasAuthority('rbac.role:approve')")}
 *
 * The {@link EndpointPermissionAuditor} fails startup if any mapped endpoint
 * lacks the annotation — the CRUD+approve matrix is not optional.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Permission {
    String value();
}
