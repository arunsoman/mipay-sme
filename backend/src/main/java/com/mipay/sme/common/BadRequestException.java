package com.mipay.sme.common;

/** Machine-readable 400: {@code code} surfaces in ProblemDetail (e.g. ACTIVATION_INVALID). */
public class BadRequestException extends RuntimeException {

    private final String code;

    public BadRequestException(String message) {
        super(message);
        this.code = null;
    }

    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code == null ? "BAD_REQUEST" : code;
    }
}
