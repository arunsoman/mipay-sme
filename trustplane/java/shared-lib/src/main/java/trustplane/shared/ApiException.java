package trustplane.shared;

/** Service exception carrying an HTTP status, error code and pre-rendered JSON body. */
public class ApiException extends RuntimeException {
    public final int status;
    public final String error;
    public final String body;

    public ApiException(int status, String error, String reason) {
        super(error + (reason == null ? "" : ": " + reason));
        this.status = status;
        this.error = error;
        this.body = ApiError.json(error, reason);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }

    public static ApiException notFound() {
        return new ApiException(404, "not_found", null);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "state_conflict", message);
    }
}
