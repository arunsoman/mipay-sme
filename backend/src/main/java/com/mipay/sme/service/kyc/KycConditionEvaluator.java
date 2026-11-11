package com.mipay.sme.service.kyc;

import java.util.List;
import java.util.Map;

/**
 * Evaluates master-table conditions identically in Java and the portals
 * (§3.5). Supported grammar:
 *
 * <pre>
 * {"field": "businessProfile.entityType", "in": ["PRIVATE_COMPANY"]}   (also eq/notEq/notIn/contains/gt/gte/lt/lte/exists)
 * {"all": [ ... ]}   {"any": [ ... ]}   {"not": { ... }}
 * {"field": "item.identificationType", "eq": "NATIONAL_ID"}            item-relative inside a LIST section
 * {"context": "orgType", "in": ["MERCHANT"]}                           resolution context
 * </pre>
 */
public final class KycConditionEvaluator {

    private KycConditionEvaluator() { }

    /** context: orgType, licenceType, country or any application fact. */
    public static boolean evaluate(Object condition, Map<String, Object> doc, Map<String, Object> item,
                                   Map<String, Object> context) {
        if (condition == null) return true;
        if (!(condition instanceof Map<?, ?> c)) return false;

        if (c.containsKey("all")) {
            Object list = c.get("all");
            return list instanceof List<?> l && l.stream().allMatch(x -> evaluate(x, doc, item, context));
        }
        if (c.containsKey("any")) {
            Object list = c.get("any");
            return list instanceof List<?> l && l.stream().anyMatch(x -> evaluate(x, doc, item, context));
        }
        if (c.containsKey("not")) {
            return !evaluate(c.get("not"), doc, item, context);
        }

        // context condition: {"context": "orgType", "in": ["MERCHANT"]}
        if (c.containsKey("context")) {
            String key = text(c.get("context"));
            Object left = context.get(key);
            return testOps(c, left);
        }

        // field condition
        if (c.containsKey("field")) {
            String path = text(c.get("field"));
            Object value = resolve(path, item, doc);
            return testOps(c, value);
        }
        return true;
    }

    private static boolean testOps(Map<?, ?> c, Object left) {
        if (c.containsKey("eq")) return eq(left, c.get("eq"));
        if (c.containsKey("notEq")) return left != null && !eq(left, c.get("notEq"));
        if (c.containsKey("in")) {
            Object v = c.get("in");
            if (!(v instanceof List<?> l)) return false;
            return left != null && (l.contains(left.toString()) || containsString(l, left));
        }
        if (c.containsKey("notIn")) {
            Object v = c.get("notIn");
            if (!(v instanceof List<?> l)) return true;
            return left == null || !(l.contains(left.toString()) || containsString(l, left));
        }
        if (c.containsKey("contains")) {
            Object needle = c.get("contains");
            if (left instanceof List<?> l) return containsString(l, needle) || l.contains(needle);
            return left != null && left.toString().equals(needle.toString());
        }
        if (c.containsKey("gt") || c.containsKey("gte") || c.containsKey("lt") || c.containsKey("lte")) {
            return compare(c, left);
        }
        if (c.containsKey("exists")) {
            boolean want = booleanOf(c.get("exists"));
            return want == (left != null && !"".equals(left));
        }
        // {"field": "x"} alone means "present"
        return left != null && !"".equals(left);
    }

    private static boolean compare(Map<?, ?> c, Object left) {
        Double l = asDouble(left);
        if (l == null) return false;
        if (c.containsKey("gt")) {
            Double r = asDouble(c.get("gt"));
            return r != null && l > r;
        }
        if (c.containsKey("gte")) {
            Double r = asDouble(c.get("gte"));
            return r != null && l >= r;
        }
        if (c.containsKey("lt")) {
            Double r = asDouble(c.get("lt"));
            return r != null && l < r;
        }
        if (c.containsKey("lte")) {
            Double r = asDouble(c.get("lte"));
            return r != null && l <= r;
        }
        return false;
    }

    private static boolean eq(Object left, Object right) {
        if (left == null || right == null) return false;
        if (left.equals(right)) return true;
        return left.toString().equals(right.toString());
    }

    private static boolean containsString(List<?> list, Object needle) {
        return list != null && needle != null && list.stream().anyMatch(x ->
                x != null && x.toString().equals(needle.toString()));
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return null;
        try {
            return Double.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean booleanOf(Object v) {
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private static String text(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * Resolves a dotted path exactly like the portals' evaluator (kyc-conditions.ts):
     * "item.x" and "section[].x" read the current list item (or OBJECT section body);
     * any other path is absolute from the document root, falling back to the current
     * item only for a bare one-segment key.
     */
    public static Object resolve(String path, Map<String, Object> item, Map<String, Object> doc) {
        if (path == null) return null;
        String[] segments = path.split("\\.");
        if (path.startsWith("item.")) {
            return walk(item != null ? item : doc, java.util.Arrays.copyOfRange(segments, 1, segments.length));
        }
        if (segments[0].endsWith("[]")) {
            return item == null ? null : walk(item, java.util.Arrays.copyOfRange(segments, 1, segments.length));
        }
        Object absolute = walk(doc, segments);
        if (absolute != null) return absolute;
        return segments.length == 1 && item != null ? item.get(segments[0]) : null;
    }

    private static Object walk(Map<String, Object> root, String[] segments) {
        Object current = root;
        for (String seg : segments) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(seg);
            if (current == null) return null;
        }
        return current;
    }
}
