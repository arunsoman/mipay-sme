package com.mipay.sme.common;

/**
 * A business rule violation with a stable machine-readable code so the
 * merchant app can branch (e.g. INSUFFICIENT_BALANCE renders screen M19).
 */
public class SimException extends RuntimeException {
    private final String code;

    public SimException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
