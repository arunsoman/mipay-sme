package trustplane.shared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an endpoint method to ONE cell of the permission grid (fapi2-design.md Appendix G):
 * {@code "<row>:<verb>"}, e.g. {@code "rbac.assignment:create"}, with the verb from the fixed
 * ten (read, create, modify, delete, submit, authorize, execute, export, administer, simulate).
 *
 * {@link PermissionRegistrar} fails startup if a mapped trustplane endpoint has neither this
 * annotation nor {@link PublicEndpoint}, and registers every cell with its endpoints in RBAC.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Permission {
    /** The cell, {@code "<row>:<verb>"}. */
    String value();

    /** Where the tenant comes from: PATH | BODY | NONE (shown on the grid screen). */
    String tenantSource() default "NONE";

    /** Shown in the catalogue and on the Roles & access screen. */
    String description() default "";
}
