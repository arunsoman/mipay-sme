package trustplane.rbac.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.HttpStatus;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.shared.Failure;
import trustplane.shared.GatewayContext;

/**
 * Cross-cutting web support shared by all admin controllers:
 *  - subject extraction from the authenticated gateway context;
 *  - server-side permission re-check (defense in depth — the gateway already
 *    enforced the same permission at the edge);
 *  - consistent error mapping to the wire contract the suite asserts on.
 */
@RestControllerAdvice
public class WebSupport {

    /** Subject of the current (gateway-authenticated) request. */
    public static String subject(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof GatewayContext ctx) return ctx.sub();
        throw Failure.toException(new Failure.MissingContext());
    }

    /** Server-side revalidation of a permission against the decision logic. */
    public static void recheck(RbacStore store, Authentication auth, String permission) {
        var d = store.checkDecision(subject(auth), "", permission);
        if (!d.allowed()) {
            throw Failure.toException(new Failure.InsufficientPermission(permission));
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> apiError(ApiException e) {
        return ResponseEntity.status(e.status).body(e.body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> generic(Exception e) {
        String msg = e.getMessage() == null ? "unexpected" : e.getMessage().replace("\"", "'");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"error\":\"internal\",\"reason\":\"" + msg + "\"}");
    }
}
