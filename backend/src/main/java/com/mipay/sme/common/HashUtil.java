package com.mipay.sme.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Small hashing helpers (TC-SEC-02/04): SHA-256 of JWT jti values is stored
 * in devices_sessions.token_hash so a database dump never yields live
 * credentials, and audit entries carry entity-state hashes.
 */
public final class HashUtil {

    private HashUtil() { }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
