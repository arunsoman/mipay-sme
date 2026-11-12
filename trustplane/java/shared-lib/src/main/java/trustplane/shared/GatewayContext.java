package trustplane.shared;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Identity context the API gateway signs (HMAC-SHA256) into the
 * {@code X-SME-Context} header before forwarding an authenticated external
 * request into the mTLS mesh. Field names and semantics MUST stay in lock-step
 * with internal/ctxauth in the Go gateway.
 */
public record GatewayContext(
        String sub,
        String uid,
        String clientId,
        String tokenId,
        String certThumb,
        List<String> scopes,
        List<String> perms,
        String tenant,
        String supplierId,
        long ts) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static GatewayContext parse(String payloadJson) {
        JsonNode n = MAPPER.readTree(payloadJson); // Jackson 3 exceptions are unchecked
        return new GatewayContext(
                n.path("sub").asText(""),
                n.path("uid").asText(""),
                n.path("client_id").asText(""),
                n.path("token_jti").asText(""),
                n.path("cert_thumb").asText(""),
                strList(n, "scopes"),
                strList(n, "perms"),
                n.path("tenant").asText(""),
                n.path("supplier_id").asText(""),
                n.path("ts").asLong());
    }

    private static List<String> strList(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = n.path(field);
        if (arr.isArray()) {
            for (JsonNode e : arr) out.add(e.asText(""));
        }
        return out;
    }

    /** The RBAC subject: the core user id when the token carries one, else the token subject. */
    public String subject() { return uid != null && !uid.isBlank() ? uid : sub; }

    public boolean hasPerm(String op) { return perms != null && perms.contains(op); }

    public boolean hasScope(String s) { return scopes != null && scopes.contains(s); }

    /** "" supplier denotes platform staff. */
    public boolean isSupplier() { return supplierId != null && !supplierId.isBlank(); }
}
