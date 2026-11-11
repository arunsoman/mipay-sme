package trustplane.shared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mapped endpoint as intentionally public (e.g. liveness probes such
 * as GET /healthz). The endpoint-permission audit skips these methods; every
 * other endpoint on a {@link CrudResource} controller must carry an explicit
 * {@link Permission} binding or startup fails.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface PublicEndpoint {
}
