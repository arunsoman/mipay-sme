package com.mipay.sme.service.kyc;

import java.util.Locale;

/** Value normalisation shared by validators (§5.2: registrationNumber validation). */
public final class KycNormalizer {

    private KycNormalizer() { }

    /** Strips / - . and spaces, uppercases: "CC/2020/06396" -> "CC202006396". */
    public static String punct(String v) {
        if (v == null) return null;
        return v.replaceAll("[/\\-.\\s]", "").toUpperCase(Locale.ROOT);
    }

    /** Name comparison form: uppercase, non-alphanumeric collapsed. */
    public static String name(String v) {
        if (v == null) return null;
        return v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    public static String normalize(String v, String mode) {
        if (v == null) return null;
        return "PUNCT".equals(mode) ? punct(v) : v.toUpperCase(Locale.ROOT);
    }
}
