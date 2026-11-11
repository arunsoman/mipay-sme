package trustplane.shared;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;

/** Shape of error responses shared across services ({"error","reason"}). */
@JsonIgnoreProperties(ignoreUnknown = true)

public record ApiError(String error, String reason) {

    public ApiError(String error) { this(error, null); }

    public static String json(String error, String reason) {
        try {
            return new ObjectMapper()
                    .writeValueAsString(new ApiError(error, reason));
        } catch (Exception e) {
            return "{\"error\":\"" + error + "\"}";
        }
    }
}
