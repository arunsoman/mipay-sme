package trustplane.shared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission vocabulary of a controller resource. Every
 * controller in every service MUST carry this annotation. With
 * {@code standard=true} (default) the full CRUD + approve matrix
 * (create, read, update, delete, approve) is registered with RBAC
 * automatically at service boot; {@code extra} adds resource-specific actions
 * (e.g. {@code submit}, {@code reject}).
 *
 * The RBAC service then needs no manual permission cataloging: permissions
 * are "picked up" from the code itself.
 */
@Repeatable(CrudResource.List.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CrudResource {

    /** Permission namespace, e.g. "rbac.supplier". */
    String value();

    /** Include the standard CRUD + approve action set (default true). */
    boolean standard() default true;

    /** Additional resource-specific actions beyond the standard set. */
    String[] extra() default {};

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        CrudResource[] value();
    }
}
