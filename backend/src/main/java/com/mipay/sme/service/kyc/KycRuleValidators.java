package com.mipay.sme.service.kyc;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import com.mipay.sme.domain.kyc.KycRule;

/**
 * The fixed validator registry (§3.3). Keys are named by kyc_rules rows;
 * missing keys fail the boot master validator.
 */
public final class KycRuleValidators {

    private KycRuleValidators() { }

    /** Finds whether a normalised value is already used by another active org. */
    public interface UniqueChecker {
        /** @return true when another active org/application already uses the value under the tag. */
        boolean used(String canonicalTag, String normalizedValue, String currentApplicationId, String currentOrgId);
    }

    static List<Map<String, Object>> items(KycValidationContext ctx, String section) {
        return ctx.facts.list(section);
    }

    static List<Object> valueList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return new ArrayList<>(l);
        return List.of(v.toString());
    }

    static boolean matchesWhenContext(Map<String, Object> params, KycValidationContext ctx) {
        Object when = params.get("whenContext");
        return KycConditionEvaluator.evaluate(when, ctx.doc, null, ctx.context);
    }

    static boolean truthy(Object v) {
        return v == Boolean.TRUE || "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** LIST_COUNT: list section item count, optionally filtered by field/value (§3.3). */
    @Component
    public static class ListCount implements KycRuleValidator {
        public String key() { return "LIST_COUNT"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            if (!matchesWhenContext(params, ctx)) return;
            String section = str(params.get("section"));
            String field = str(params.get("field"));
            int min = num(params.get("min"), 0);
            int max = num(params.get("max"), Integer.MAX_VALUE);
            List<Object> values = valueList(params.get("value"));
            long count = items(ctx, section).stream()
                    .filter(item -> {
                        Object v = item.get(field);
                        if (values.isEmpty()) return v != null;
                        return v != null && (values.contains(v.toString())
                                || (v instanceof List<?> l && l.stream().anyMatch(x -> values.contains(x.toString()))));
                    })
                    .count();
            if (count < min) {
                ctx.error(section, "at least " + min + " matching " + (values.isEmpty() ? "entries"
                        : field + "=" + values) + " required (found " + count + ")");
            } else if (count > max) {
                ctx.error(section, "at most " + max + " matching " + (values.isEmpty() ? "entries"
                        : field + "=" + values) + " allowed (found " + count + ")");
            }
        }
    }

    /** SUM_MAX: combined value of a numeric field over a list section (e.g. ownership <= 100). */
    @Component
    public static class SumMax implements KycRuleValidator {
        public String key() { return "SUM_MAX"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            String section = str(params.get("section"));
            String field = str(params.get("field"));
            BigDecimal max = BigDecimal.valueOf(num(params.get("max"), 100));
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> item : items(ctx, section)) {
                Object v = item.get(field);
                if (v instanceof Number n) total = total.add(BigDecimal.valueOf(n.doubleValue()));
                else if (v != null) {
                    try {
                        total = total.add(new BigDecimal(v.toString()));
                    } catch (NumberFormatException ignored) {
                        // non-numeric values fail the field-level type check
                    }
                }
            }
            if (total.doubleValue() > max.doubleValue()) {
                ctx.error(section + "." + field,
                        str(params.get("message"), "combined " + field + " is "
                                + total.stripTrailingZeros().toPlainString() + " (max " + max + ")"));
            }
        }
    }

    /** ANY_GTE: at least one owner >= threshold, or a control basis is declared (FIA Schedule 5). */
    @Component
    public static class AnyGte implements KycRuleValidator {
        public String key() { return "ANY_GTE"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            String section = str(params.getOrDefault("section", "ubos"));
            List<Object> fields = valueList(params.get("fields"));
            Object threshold = params.get("thresholdPct");
            double pct;
            if (threshold == null) {
                pct = ctx.intParam("uboThresholdPct", 25);
            } else if (threshold.toString().startsWith("@")) {
                pct = ctx.intParam(threshold.toString().substring(1), 25);
            } else {
                try {
                    pct = Double.parseDouble(threshold.toString());
                } catch (NumberFormatException e) {
                    pct = ctx.intParam("uboThresholdPct", 25);
                }
            }
            String controlBasis = str(params.getOrDefault("controlBasis", "controlBasis"));
            for (Map<String, Object> item : items(ctx, section)) {
                for (Object f : fields) {
                    Object v = item.get(str(f));
                    if (v instanceof Number n && n.doubleValue() >= pct) return;
                }
                if (item.get(controlBasis) != null && !str(item.get(controlBasis)).isBlank()) return;
            }
            ctx.error(section, str(params.get("message"),
                    "Add a beneficial owner with at least " + (int) pct
                            + "% ownership or voting rights, or declare the control basis."));
        }
    }

    /** MIN_AGE: every date-of-birth must make the person at least minAge. */
    @Component
    public static class MinAge implements KycRuleValidator {
        public String key() { return "MIN_AGE"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            String section = str(params.getOrDefault("section", "ubos"));
            String field = str(params.getOrDefault("field", "dateOfBirth"));
            int minAge = num(params.get("minAge"), ctx.intParam("minOwnerAge", 18));
            for (Map<String, Object> item : items(ctx, section)) {
                Object v = item.get(field);
                LocalDate dob = parseDate(v);
                int i = items(ctx, section).indexOf(item);
                if (dob == null) continue;     // completeness is the field validator's job
                if (Period.between(dob, LocalDate.now()).getYears() < minAge) {
                    ctx.error(section + "[" + i + "]." + field, "owner must be at least " + minAge);
                }
            }
        }
    }

    /** ID_MATCHES_DOB: NA national ID first six digits equal YYMMDD of the date of birth. */
    @Component
    public static class IdMatchesDob implements KycRuleValidator {
        private static final Pattern YYMMDD = Pattern.compile("\\d{6}");
        private static final DateTimeFormatter ID_DATE = DateTimeFormatter.ofPattern("yyMMdd");

        public String key() { return "ID_MATCHES_DOB"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            String section = str(params.getOrDefault("section", "ubos"));
            String idField = str(params.getOrDefault("idField", "identificationNumber"));
            String dobField = str(params.getOrDefault("dobField", "dateOfBirth"));
            String typeField = str(params.getOrDefault("typeField", "identificationType"));
            String type = str(params.getOrDefault("idType", "NATIONAL_ID"));
            List<Map<String, Object>> all = items(ctx, section);
            for (int i = 0; i < all.size(); i++) {
                Map<String, Object> item = all.get(i);
                if (!type.equals(item.get(typeField))) continue;
                String id = str(item.get(idField));
                LocalDate dob = parseDate(item.get(dobField));
                if (id == null || dob == null || !YYMMDD.matcher(id.substring(0, Math.min(6, id.length()))).matches()) {
                    continue;
                }
                if (!ID_DATE.format(dob).equals(id.substring(0, 6))) {
                    ctx.error(section + "[" + i + "]." + idField,
                            "identity number must start with " + ID_DATE.format(dob) + " (from the date of birth)");
                }
            }
        }
    }

    /** NAME_MATCH: bank account holder should match the legal or trading name (warning). */
    @Component
    public static class NameMatch implements KycRuleValidator {
        public String key() { return "NAME_MATCH"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            String section = str(params.getOrDefault("section", "bankAccounts"));
            String nameField = str(params.getOrDefault("nameField", "beneficiaryName"));
            List<Object> against = valueList(params.get("against"));
            for (int i = 0; i < items(ctx, section).size(); i++) {
                Map<String, Object> item = items(ctx, section).get(i);
                String holder = str(item.get(nameField));
                if (holder == null || holder.isBlank()) continue;
                String norm = KycNormalizer.name(holder);
                boolean match = against.stream().map(a -> KycConditionEvaluator.resolve(str(a), null, ctx.doc))
                        .anyMatch(v -> v != null && KycNormalizer.name(v.toString()).equals(norm));
                if (!match) {
                    ctx.warning(section + "[" + i + "]." + nameField,
                            "account holder does not match the legal or trading name");
                }
            }
        }
    }

    /** NOT_BEFORE: a date must not be before today (or must be, with direction: PAST). */
    @Component
    public static class NotBefore implements KycRuleValidator {
        public String key() { return "NOT_BEFORE"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            String path = str(params.get("field"));
            Object v = KycConditionEvaluator.resolve(path, null, ctx.doc);
            LocalDate d = parseDate(v);
            if (d == null) return;
            LocalDate today = LocalDate.now();
            boolean futureAllowed = truthy(params.get("futureAllowed"));
            if (d.isAfter(today) && !futureAllowed) {
                ctx.error(path, "date cannot be in the future");
            }
        }
    }

    /** VALUE_EQ: a field on a list item must equal a value (sole proprietor holds 100%). */
    @Component
    public static class ValueEq implements KycRuleValidator {
        public String key() { return "VALUE_EQ"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            if (!matchesWhenContext(params, ctx)) return;
            String section = str(params.get("section"));
            String field = str(params.get("field"));
            Object expected = params.get("eq");
            List<Map<String, Object>> all = items(ctx, section);
            if (all.isEmpty()) return;
            boolean anyMatch = all.stream().anyMatch(item -> {
                Object v = item.get(field);
                return v != null && v.toString().equals(expected.toString());
            });
            if (!anyMatch) {
                ctx.error(section + "." + field,
                        "at least one " + section + " entry must have " + field + " = " + expected);
            }
        }
    }

    /** ROLE_PRESENT: at least min items with a role value inside MULTI roles (trusts need a trustee). */
    @Component
    public static class RolePresent implements KycRuleValidator {
        public String key() { return "ROLE_PRESENT"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            if (!matchesWhenContext(params, ctx)) return;
            String section = str(params.get("section"));
            String field = str(params.getOrDefault("field", "roles"));
            List<Object> values = valueList(params.get("values"));
            List<Map<String, Object>> all = items(ctx, section);
            boolean present = all.stream().anyMatch(item -> {
                Object v = item.get(field);
                if (!(v instanceof List<?> l)) return v != null && values.contains(v.toString());
                for (Object x : l) {
                    if (x != null && values.contains(x.toString())) return true;
                }
                return false;
            });
            if (!present) {
                ctx.error(section, "at least one " + section
                        + " entry must hold role " + values + " for a trust");
            }
        }
    }

    /**
     * ITEM_PATTERN: a field of each list item must match a pattern, but only for the items
     * where a condition holds - e.g. the 11-digit format only for a NATIONAL_ID, so a passport
     * number is never held to it. params: section, field, pattern, when (condition), message.
     */
    @Component
    public static class ItemPattern implements KycRuleValidator {
        public String key() { return "ITEM_PATTERN"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            String section = str(params.get("section"));
            String field = str(params.get("field"));
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(str(params.get("pattern")));
            Object when = params.get("when");
            List<Map<String, Object>> all = items(ctx, section);
            for (int i = 0; i < all.size(); i++) {
                Map<String, Object> item = all.get(i);
                if (!KycConditionEvaluator.evaluate(when, ctx.doc, item, ctx.context)) continue;
                String v = str(item.get(field));
                if (v == null || v.isBlank()) continue;   // completeness is the field check's job
                if (!pattern.matcher(v.trim()).matches()) {
                    ctx.error(section + "[" + i + "]." + field, str(params.get("message"), "invalid format"));
                }
            }
        }
    }

    /** PEP_EDD: a PIP needs enhanced due diligence; auto-approval is never applied (§6.2). */
    @Component
    public static class PepEdd implements KycRuleValidator {
        public String key() { return "PEP_EDD"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            if (ctx.facts.anyPip()) {
                // PIPs are routed to senior management by the high-risk-or-pep contributor;
                // informational only, never an error.
                ctx.warning("ubos", "a prominent influential person is declared: enhanced due diligence applies");
            }
        }
    }

    /** UNIQUE_IN_PLATFORM: registration/TIN/owner IDs used by another active org (§3.3, §8). */
    @Component
    public static class UniqueInPlatform implements KycRuleValidator {
        public String key() { return "UNIQUE_IN_PLATFORM"; }

        public void validate(Map<String, Object> params, KycValidationContext ctx) {
            String section = str(params.get("section"));
            String field = str(params.get("field"));
            String tag = str(params.getOrDefault("tag", "UNKNOWN"));
            KycRule.Severity severity = "WARNING".equalsIgnoreCase(str(params.get("severity")))
                    ? KycRule.Severity.WARNING : KycRule.Severity.ERROR;
            KycUniqueChecker checker = ctx.uniqueChecker;
            if (checker == null) return;
            List<Map<String, Object>> all = items(ctx, section);
            for (int i = 0; i < all.size(); i++) {
                String value = KycNormalizer.normalize(str(all.get(i).get(field)), str(params.get("normalize")));
                if (value == null || value.isBlank()) continue;
                String path = section + "[" + i + "]." + field;
                if (checker.used(tag, value, ctx.facts.applicationId(), ctx.facts.orgId())) {
                    if (severity == KycRule.Severity.WARNING && section.equals("ubos")) {
                        ctx.warning(path, "this value is already used in the platform (warning)");
                    } else if (severity == KycRule.Severity.WARNING) {
                        ctx.warning(path, "this value is already used in the platform (warning)");
                    } else {
                        ctx.error(path, "This value is already used by another active organization.");
                    }
                }
            }
        }
    }

    static KycUniqueChecker noop() {
        return (tag, value, appId, orgId) -> false;
    }

    // ---------------------------------------------------------------- helpers

    private static String str(Object v, String def) {
        return v == null || str(v).isBlank() ? def : str(v);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int num(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static LocalDate parseDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        try {
            return LocalDate.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Interface for the UNIQUE_IN_PLATFORM checker, wired by the application service. */
    public interface KycUniqueChecker {
        boolean used(String canonicalTag, String normalizedValue, String currentApplicationId, String currentOrgId);
    }
}
