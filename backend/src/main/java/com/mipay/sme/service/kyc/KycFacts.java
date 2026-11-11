package com.mipay.sme.service.kyc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed view of the KYC document built from canonical tags (§4). Plugins,
 * providers and the risk scorer receive this instead of country-specific JSON.
 */
public record KycFacts(Map<String, Object> byTag, Map<String, Object> doc,
                       String entityType, String orgType, String country, String licenceType,
                       String applicationId, String orgId) {

    public Optional<String> tag(String canonicalTag) {
        Object v = byTag.get(canonicalTag);
        return v == null || "".equals(v) ? Optional.empty() : Optional.of(v.toString());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(String section) {
        Object v = doc.get(section);
        return v instanceof List<?> l ? l.stream()
                .filter(x -> x instanceof Map)
                .map(x -> (Map<String, Object>) x)
                .toList() : List.of();
    }

    public boolean anyPip() {
        return list("ubos").stream().anyMatch(u ->
                Boolean.TRUE.equals(u.get("politicallyExposedPerson")));
    }

    public boolean isSoleProprietor() {
        return "SOLE_PROPRIETOR".equals(entityType);
    }
}
