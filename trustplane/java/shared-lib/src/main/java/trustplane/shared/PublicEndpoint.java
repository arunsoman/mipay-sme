package trustplane.shared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mapped endpoint that carries no grid cell: a liveness probe, or a mesh-internal
 * endpoint whose caller is identified by its mTLS certificate instead of a gateway context
 * (the reason says which). Every other endpoint must carry {@link Permission}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface PublicEndpoint {
    String value() default "";
}
