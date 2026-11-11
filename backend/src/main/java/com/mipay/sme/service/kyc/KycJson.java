package com.mipay.sme.service.kyc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON helpers shared by the KYC engine. */
public final class KycJson {

    private static volatile ObjectMapper mapper;

    private KycJson() { }

    public static void setMapper(ObjectMapper objectMapper) {
        mapper = objectMapper;
    }

    public static ObjectMapper mapper() {
        ObjectMapper m = mapper;
        if (m == null) throw new IllegalStateException("KycJson mapper not initialised");
        return m;
    }

    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper().readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return mapper().readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public static List<Map<String, Object>> parseListMap(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper().readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String write(Object o) {
        try {
            return mapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("KYC JSON could not be serialised", e);
        }
    }

    public static com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper().readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    public static com.fasterxml.jackson.databind.JsonNode readTree(Map<String, Object> map) {
        if (map == null) return null;
        return readTree(write(map));
    }

    /** Parses a stored workflow plan; stages come back in a mutable list (plans only grow). */
    public static com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan parsePlan(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper().readValue(json, com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan.class);
        } catch (Exception e) {
            return null;
        }
    }
}
