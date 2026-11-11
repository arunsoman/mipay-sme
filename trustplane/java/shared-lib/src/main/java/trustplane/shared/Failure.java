package trustplane.shared;

/**
 * Sealed taxonomy of every validation failure the platform can produce.
 * Closed hierarchy: the compiler forces HTTP mapping to cover every case
 * (exhaustive pattern switch below) — no missing error semantics.
 *
 * Transport: Failure instances are convertible to the wire form
 * {"error":code,"reason":message} through {@link #toException()}.
 */
public sealed interface Failure
        permits Failure.NotFound, Failure.Forbidden, Failure.BadRequest,
                Failure.Conflict, Failure.NoMatchingGrant, Failure.ScopeExceeded,
                Failure.InsufficientPermission, Failure.MissingContext {

    String code();

    String message();

    /** 404 — the referenced resource does not exist. */
    record NotFound(String subject) implements Failure {
        public String code() { return "not_found"; }
        public String message() { return "resource not found: " + subject; }
    }

    /** 403 with a specific code — membership, supplier_isolation, maker_checker, ... */
    record Forbidden(String code, String message) implements Failure {
        public String code() { return code; }
        public String message() { return message; }
    }

    /** 400 — structurally invalid request. */
    record BadRequest(String message) implements Failure {
        public String code() { return "bad_request"; }
        public String message() { return message; }
    }

    /** 409 — state machine or concurrency violation (e.g. non-draft edits). */
    record Conflict(String message) implements Failure {
        public String code() { return "state_conflict"; }
        public String message() { return message; }
    }

    /** 403 — no active root grant covers (supplier, role, scope, tenant). */
    record NoMatchingGrant() implements Failure {
        public String code() { return "no_matching_grant"; }
        public String message() { return "root RBAC has not delegated this role to the supplier"; }
    }

    /** 403 — a grant covers the role but the requested scope exceeds it. */
    record ScopeExceeded() implements Failure {
        public String code() { return "scope_exceeds_grant"; }
        public String message() { return "requested scope exceeds the root delegation grant"; }
    }

    /** 403 — the subject does not hold the required CRUD permission. */
    record InsufficientPermission(String permission) implements Failure {
        public String code() { return "insufficient_permission"; }
        public String message() { return "subject lacks " + permission; }
    }

    /** 401 — no verifiable gateway context on the request. */
    record MissingContext() implements Failure {
        public String code() { return "unauthenticated"; }
        public String message() { return "missing gateway context"; }
    }

    // ----- semantic factories ----------------------------------------

    static Forbidden membership(String message) {
        return new Forbidden("membership", message);
    }

    static Forbidden creatorOnly(String message) {
        return new Forbidden("creator_only", message);
    }

    static Forbidden isolation(String message) {
        return new Forbidden("supplier_isolation", message);
    }

    static Forbidden forbidden(String message) {
        return new Forbidden("forbidden", message);
    }

    /** Exhaustive HTTP mapping — new Failure subtypes must be mapped here. */
    static ApiException toException(Failure f) {
        return switch (f) {
            case NotFound n -> new ApiException(404, n.code(), n.message());
            case Forbidden g -> new ApiException(403, g.code(), g.message());
            case BadRequest b -> new ApiException(400, b.code(), b.message());
            case Conflict c -> new ApiException(409, c.code(), c.message());
            case NoMatchingGrant ignored -> new ApiException(403, ignored.code(), ignored.message());
            case ScopeExceeded ignored -> new ApiException(403, ignored.code(), ignored.message());
            case InsufficientPermission p -> new ApiException(403, p.code(), p.message());
            case MissingContext ignored -> new ApiException(401, ignored.code(), ignored.message());
        };
    }
}
