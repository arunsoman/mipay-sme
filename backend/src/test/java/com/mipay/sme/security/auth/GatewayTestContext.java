package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Mints the gateway's signed identity context for tests (cutover plan P3.8 helper): the same
 * HMAC-SHA256 wire format the GatewayIdentityFilter verifies — base64url(payload).base64url(mac).
 * Tests mint with the shared ctx secret (build/test-pki/_vol/ctx-secret) and send the value as
 * the X-SME-Context header.
 */
public final class GatewayTestContext {
    private GatewayTestContext() { }

    public record Call(String issuer, String subject, String uid, String acr, List<String> amr,
                       long authTime, String sid, String clientId) { }

    public static String header(String secretFile, Call call) {
        try {
            byte[] key = Files.readAllBytes(Path.of(secretFile));
            return header(key, call);
        } catch (Exception e) {
            throw new IllegalStateException("gateway ctx secret unreadable: " + secretFile, e);
        }
    }

    public static String header(byte[] key, Call call) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String payload = mapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
                put("iss", call.issuer());
                put("sub", call.subject());
                put("uid", call.uid());
                put("acr", call.acr());
                put("amr", call.amr() == null ? List.of() : call.amr());
                put("auth_time", call.authTime());
                put("sid", call.sid());
                put("client_id", call.clientId());
                put("ts", Instant.now().getEpochSecond());
            }});
            String payload64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signature = mac.doFinal(payload64.getBytes(StandardCharsets.US_ASCII));
            return payload64 + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("gateway context minting failed", e);
        }
    }

    /** True when the header passes HMAC verification with the given key (test-side sanity). */
    public static boolean verifies(byte[] key, String header) {
        try {
            String[] parts = header.split("\\.", -1);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] expected = mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII));
            return MessageDigest.isEqual(expected,
                    Base64.getUrlDecoder().decode(parts.length == 2 ? parts[1] : "zz"));
        } catch (Exception e) {
            return false;
        }
    }
}
