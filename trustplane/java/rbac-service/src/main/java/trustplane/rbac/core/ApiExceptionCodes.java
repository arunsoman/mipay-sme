package trustplane.rbac.core;

import trustplane.shared.ApiException;
import trustplane.shared.Failure;

/**
 * Thin bridge from the shared sealed {@link Failure} taxonomy to HTTP
 * exceptions — the RBAC service's authoritative error vocabulary.
 */
public final class ApiExceptionCodes {

    private ApiExceptionCodes() {}

    public static ApiException forbidden() {
        return Failure.toException(Failure.forbidden("actor may not perform this operation"));
    }

    public static ApiException notFound(String reason) {
        return Failure.toException(new Failure.NotFound(reason == null ? "" : reason));
    }

    public static ApiException membership(String message) {
        return Failure.toException(Failure.membership(message));
    }

    public static ApiException noMatchingGrant() {
        return Failure.toException(new Failure.NoMatchingGrant());
    }

    public static ApiException scopeExceeded() {
        return Failure.toException(new Failure.ScopeExceeded());
    }
}
