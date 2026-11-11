package com.mipay.sme.service.kyc;

import com.mipay.sme.domain.kyc.KycField;
import com.mipay.sme.domain.kyc.KycListItem;
import com.mipay.sme.domain.kyc.KycSection;
import com.mipay.sme.service.kyc.KycMasterService.ProfileBundle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Schema-driven document validation (§4): unknown keys are rejected, hidden
 * fields are stripped, types/lengths/patterns/list membership/conditions are
 * enforced per the master, and SUBMIT adds completeness plus the cross-field
 * rules. Drafts may be incomplete, never malformed (SAVE checks shape only).
 */
@Service
@RequiredArgsConstructor
public class KycDocValidator {

    public record Result(Map<String, Object> doc, List<KycValidationContext.Problem> problems) { }

    private final KycMasterService master;
    private final KycPluginRegistry plugins;

    // ------------------------------------------------------------------ entry points

    /** Draft save: shape only. The document may be sparse but every present value must be well-formed. */
    public Result validateSave(Map<String, Object> doc, ProfileBundle bundle,
                               Map<String, Object> context, KycRuleValidators.KycUniqueChecker unique) {
        return run(doc, bundle, context, unique, false, null, null, null, null);
    }

    /** Save with identities: UNIQUE_IN_PLATFORM excludes the current application and org. */
    public Result validateSave(Map<String, Object> doc, ProfileBundle bundle, Map<String, Object> context,
                               KycRuleValidators.KycUniqueChecker unique, String applicationId, String orgId,
                               String entityType, String orgType) {
        return run(doc, bundle, context, unique, false, applicationId, orgId, entityType, orgType);
    }

    /** Submit: completeness + cross-field rules. */
    public Result validateSubmit(Map<String, Object> doc, ProfileBundle bundle,
                                 Map<String, Object> context, KycRuleValidators.KycUniqueChecker unique) {
        return run(doc, bundle, context, unique, true, null, null, null, null);
    }

    /** Submit with identities. */
    public Result validateSubmit(Map<String, Object> doc, ProfileBundle bundle, Map<String, Object> context,
                                 KycRuleValidators.KycUniqueChecker unique, String applicationId, String orgId,
                                 String entityType, String orgType) {
        return run(doc, bundle, context, unique, true, applicationId, orgId, entityType, orgType);
    }

    // ------------------------------------------------------------------ core

    @SuppressWarnings("unchecked")
    private Result run(Map<String, Object> input, ProfileBundle bundle, Map<String, Object> context,
                       KycRuleValidators.KycUniqueChecker unique, boolean submit,
                       String applicationId, String orgId, String entityType, String orgType) {
        Map<String, Object> doc = new LinkedHashMap<>(input);
        KycValidationContext ctx = new KycValidationContext(bundle,
                factsOf(doc, bundle, applicationId, orgId, entityType, orgType), doc, context, unique);

        // sameAsRegistered copy-on-save (§5.2): a non-registered address the owner
        // marks "same as registered" inherits the registered values before checks
        copySameAsRegistered(doc);

        // 1) hidden sections and fields are removed from the stored document (§4)
        List<KycSection> visibleSections = new ArrayList<>();
        for (KycSection section : bundle.sections()) {
            if (KycConditionEvaluator.evaluate(KycJson.parse(section.getVisibleWhen()), doc, null, context)) {
                visibleSections.add(section);
            } else {
                doc.remove(section.getKey());
            }
        }
        for (KycSection section : visibleSections) {
            if (section.getKind() == KycSection.Kind.OBJECT) {
                Map<String, Object> body = asMap(section, doc);
                for (KycField field : bundle.sectionFields(section.getKey())) {
                    if (!KycConditionEvaluator.evaluate(KycJson.parse(field.getVisibleWhen()), doc, body, context)) {
                        body.remove(field.getKey());
                    }
                }
            } else if (section.getKind() == KycSection.Kind.LIST) {
                List<Map<String, Object>> items = asList(doc, section.getKey());
                for (Map<String, Object> item : items) {
                    for (KycField field : bundle.sectionFields(section.getKey())) {
                        if (!KycConditionEvaluator.evaluate(KycJson.parse(field.getVisibleWhen()), doc, item, context)) {
                            item.remove(field.getKey());
                        }
                    }
                }
            }
        }

        // 2) unknown keys are rejected with the list of them (§4)
        List<String> unknown = new ArrayList<>();
        for (String key : doc.keySet()) {
            if (bundle.sections().stream().noneMatch(s -> s.getKey().equals(key))) {
                unknown.add(key);
            }
        }
        for (KycSection section : visibleSections) {
            List<KycField> fields = bundle.sectionFields(section.getKey());
            if (section.getKind() == KycSection.Kind.OBJECT) {
                for (String key : asMap(section, doc).keySet()) {
                    if (fields.stream().noneMatch(f -> f.getKey().equals(key))) unknown.add(section.getKey() + "." + key);
                }
            } else if (section.getKind() == KycSection.Kind.LIST) {
                for (Map<String, Object> item : asList(doc, section.getKey())) {
                    for (String key : item.keySet()) {
                        if (fields.stream().noneMatch(f -> f.getKey().equals(key))) {
                            unknown.add(section.getKey() + "[]." + key);
                        }
                    }
                }
            }
        }
        if (!unknown.isEmpty()) {
            ctx.error("", "unknown keys in the KYC document: " + String.join(", ", unknown));
            return new Result(doc, ctx.all());
        }

        // 3) per-field checks
        for (KycSection section : visibleSections) {
            List<KycField> fields = bundle.sectionFields(section.getKey());
            if (section.getKind() == KycSection.Kind.DOCUMENTS) continue;   // D4: off for NA/PSP

            if (section.getKind() == KycSection.Kind.OBJECT) {
                Map<String, Object> body = asMap(section, doc);
                for (KycField field : fields) {
                    // hidden fields were stripped in step 1 and are never required
                    if (!visible(field, doc, body, context)) continue;
                    checkField(ctx, section.getKey(), body, field, submit);
                }
            } else { // LIST
                List<Map<String, Object>> items = asList(doc, section.getKey());
                if (items.size() < coalesceMin(section) ) {
                    if (submit || itemsExist(doc, section.getKey())) {
                        ctx.error(section.getKey(),
                                "at least " + coalesceMin(section) + " " + section.getKey() + " required"
                                        + (items.isEmpty() ? " (none provided)" : " (found " + items.size() + ")"));
                    }
                }
                if (section.getMaxItems() != null && items.size() > section.getMaxItems()) {
                    ctx.error(section.getKey(),
                            "at most " + section.getMaxItems() + " " + section.getKey()
                                    + " allowed (found " + items.size() + ")");
                }
                for (int i = 0; i < items.size(); i++) {
                    for (KycField field : fields) {
                        if (!visible(field, doc, items.get(i), context)) continue;
                        checkField(ctx, section.getKey() + "[" + i + "]", items.get(i), field, submit);
                    }
                }
            }
        }

        // 4) derived fields are filled from the master, not edited (§3.2)
        for (KycSection section : visibleSections) {
            if (section.getKind() == KycSection.Kind.DOCUMENTS) continue;
            List<KycField> fields = bundle.sectionFields(section.getKey());
            if (section.getKind() == KycSection.Kind.OBJECT) {
                Map<String, Object> body = asMap(section, doc);
                for (KycField field : fields) {
                    if (field.getDerivedFrom() != null) {
                        Object derived = deriveValue(field, List.of(body));
                        if (derived != null) body.put(field.getKey(), derived);
                    }
                }
            } else if (section.getKind() == KycSection.Kind.LIST) {
                List<Map<String, Object>> items = asList(doc, section.getKey());
                for (KycField field : fields) {
                    if (field.getDerivedFrom() != null && "mcc".equals(field.getDerivedFrom())) {
                        Object derived = deriveValue(field, items);
                        if (derived != null) {
                            for (Map<String, Object> item : items) {
                                item.put(field.getKey(), derived);
                            }
                        }
                    }
                }
            }
        }

        // 5) SAVE + SUBMIT rules from the master (§3.3)
        List<KycRuleBundle> rules = submit ? rulesOf(bundle.submitRules()) : rulesOf(bundle.saveRules());
        for (KycRuleBundle rule : rules) {
            KycRuleValidator validator;
            try {
                validator = plugins.ruleValidator(rule.validatorKey);
            } catch (IllegalArgumentException e) {
                ctx.error("", "unknown validator '" + rule.validatorKey + "' in rule " + rule.code);
                continue;
            }
            try {
                validator.validate(rule.params, ctx);
            } catch (Exception e) {
                ctx.error("", "rule " + rule.code + " failed to run: " + e.getMessage());
            }
        }
        return new Result(doc, ctx.all());
    }

    // ------------------------------------------------------------------ machinery

    private KycFacts factsOf(Map<String, Object> doc, ProfileBundle bundle, String applicationId,
                             String orgId, String entityType, String orgType) {
        Map<String, Object> byTag = new LinkedHashMap<>();
        for (Map.Entry<String, KycField> e : bundle.allFieldsBySectionKey().entrySet()) {
            KycField field = e.getValue();
            if (field.getCanonicalTag() == null) continue;
            String[] parts = e.getKey().split("\\.", 2);
            if (parts.length < 2) continue;
            Object body = doc.get(parts[0]);
            Object value = body instanceof Map<?, ?> m ? ((Map<?, ?>) m).get(parts[1]) : null;
            if (value == null && body instanceof List<?> l) {
                for (Object item : l) {
                    if (item instanceof Map<?, ?> m) {
                        Object v = ((Map<?, ?>) m).get(parts[1]);
                        if (v != null && !isEncrypted(v)) {
                            value = v;
                            break;
                        }
                    }
                }
            }
            if (value != null && !isEncrypted(value)) {
                if (!(value instanceof List<?> list)) {
                    byTag.put(field.getCanonicalTag(), value);
                } else if (!list.isEmpty()) {
                    byTag.put(field.getCanonicalTag(), list.get(0));
                }
            }
        }
        return new KycFacts(byTag, doc, entityType, orgType, null, null, applicationId, orgId);
    }

    /** §5.2: when the box is ticked the other address fields are copied on save. */
    @SuppressWarnings("unchecked")
    private void copySameAsRegistered(Map<String, Object> doc) {
        Object raw = doc.get("addresses");
        if (!(raw instanceof List<?> list)) return;
        Map<String, Object> registered = null;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object o : list) if (o instanceof Map) items.add((Map<String, Object>) o);
        for (Map<String, Object> item : items) {
            if ("REGISTERED".equals(item.get("addressType"))) registered = item;
        }
        if (registered == null) return;
        for (Map<String, Object> item : items) {
            if (!Boolean.TRUE.equals(item.get("sameAsRegistered"))) continue;
            for (String key : List.of("streetLine", "town", "region", "postalCode", "country")) {
                item.put(key, registered.get(key));
            }
        }
    }

    private static boolean visible(KycField field, Map<String, Object> doc, Map<String, Object> holder,
                                   Map<String, Object> context) {
        return KycConditionEvaluator.evaluate(KycJson.parse(field.getVisibleWhen()), doc, holder, context);
    }

    private static boolean isEncrypted(Object v) {
        return v instanceof Map<?, ?> m && m.containsKey("enc");
    }

    private void checkField(KycValidationContext ctx, String pathPrefix, Map<String, Object> holder,
                            KycField field, boolean submit) {
        String path = pathPrefix + "." + field.getKey();
        Object value = holder.get(field.getKey());

        // TAX_ID legacy pad: a 7-digit reference is stored with a leading 0 (§5.2)
        if ("TAX_ID".equals(field.getCanonicalTag()) && value != null && value.toString().matches("\\d{7}")) {
            value = "0" + value;
            holder.put(field.getKey(), value);
        }

        boolean requiredNow = isRequired(ctx, field, holder, submit);
        if (isNull(value)) {
            if (requiredNow && submit) {
                ctx.error(path, field.getLabel() + " required");
            }
            return;
        }
        checkShape(ctx, path, holder, field, value);
    }

    private boolean isNull(Object value) {
        return value == null || "".equals(value);
    }

    private boolean isRequired(KycValidationContext ctx, KycField field, Map<String, Object> item, boolean submit) {
        switch (field.getRequired()) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case WHEN:
            default:
                if (!submit) return false;
                return KycConditionEvaluator.evaluate(KycJson.parse(field.getRequiredWhen()), ctx.doc, item, ctx.context);
        }
    }

    private void checkShape(KycValidationContext ctx, String path, Map<String, Object> holder,
                            KycField field, Object value) {
        switch (field.getDataType()) {
            case TEXT -> {
                String s = value.toString();
                if (field.getMinLen() != null && s.length() < field.getMinLen()) {
                    ctx.error(path, "at least " + field.getMinLen() + " characters required");
                }
                if (field.getMaxLen() != null && s.length() > field.getMaxLen()) {
                    ctx.error(path, "at most " + field.getMaxLen() + " characters allowed");
                }
                if (field.getPattern() != null) {
                    String checked = field.getNormalize() == KycField.Normalize.PUNCT
                            ? KycNormalizer.punct(s) : s;
                    if (!Pattern.matches(field.getPattern(), checked)) {
                        ctx.error(path, "invalid format");
                    }
                }
            }
            case NUMBER, MONEY, PERCENT -> {
                BigDecimal n;
                try {
                    n = new BigDecimal(value.toString());
                } catch (NumberFormatException e) {
                    ctx.error(path, "must be a number");
                    return;
                }
                if (field.getMinValue() != null && n.compareTo(field.getMinValue()) < 0) {
                    ctx.error(path, "must be at least " + plain(field.getMinValue()));
                }
                if (field.getMaxValue() != null && n.compareTo(field.getMaxValue()) > 0) {
                    ctx.error(path, "must be at most " + plain(field.getMaxValue()));
                }
            }
            case DATE -> {
                LocalDate d;
                try {
                    d = LocalDate.parse(value.toString());
                } catch (DateTimeParseException e) {
                    ctx.error(path, "must be a date (yyyy-MM-dd)");
                    return;
                }
                LocalDate today = LocalDate.now();
                if (field.getDateRule() == KycField.DateRule.PAST && !d.isBefore(today)) {
                    ctx.error(path, "must be in the past");
                }
                if (field.getDateRule() == KycField.DateRule.FUTURE && !d.isAfter(today)) {
                    ctx.error(path, "must be in the future");
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean) && !"true".equalsIgnoreCase(value.toString())
                        && !"false".equalsIgnoreCase(value.toString())) {
                    ctx.error(path, "must be true or false");
                }
            }
            case ENUM, COUNTRY -> checkChoice(ctx, path, holder, field, value);
            case MULTI_ENUM -> {
                if (!(value instanceof List<?> list)) {
                    ctx.error(path, "must be a list of choices");
                    return;
                }
                for (Object v : list) {
                    checkChoice(ctx, path, holder, field, v);
                }
            }
            case EMAIL -> {
                if (!value.toString().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    ctx.error(path, "must be a valid email address");
                }
            }
            case URL -> {
                String s = value.toString();
                if (!s.startsWith("https://")) {
                    ctx.error(path, "must be an https URL");
                }
            }
            case PHONE -> {
                if (!value.toString().matches("\\+?[0-9\\-\\s()]{6,20}")) {
                    ctx.error(path, "must be a valid phone number");
                }
            }
        }
    }

    private void checkChoice(KycValidationContext ctx, String path, Map<String, Object> holder,
                             KycField field, Object value) {
        if (field.getListCode() == null || isNull(value)) return;
        String parent = null;
        if (field.getListParentField() != null && holder != null) {
            Object pv = holder.get(field.getListParentField());
            parent = pv == null ? null : pv.toString();
        }
        if (!master.itemValid(field.getListCode(), parent, value.toString())) {
            ctx.error(path, "'" + value + "' is not a valid choice"
                    + (parent != null ? " for " + field.getListParentField() + "=" + parent : ""));
        }
    }

    /**
     * A derived field takes its value from the master: for industryVertical the
     * INDUSTRY_VERTICAL item whose parent_value equals the first mcc (or the
     * MCC item's vertical attribute when no exact parent match exists).
     */
    private Object deriveValue(KycField field, List<Map<String, Object>> holders) {
        if (field.getListCode() == null) return null;
        for (Map<String, Object> holder : holders) {
            Object source = holder.get(field.getDerivedFrom());
            if (isNull(source)) continue;
            for (KycListItem item : master.listItems(field.getListCode(), source.toString())) {
                return item.getValue();
            }
            KycListItem sourceItem = master.item("MCC", source.toString());
            if (sourceItem != null && sourceItem.getAttributes() != null) {
                Map<String, Object> attrs = KycJson.parseMap(sourceItem.getAttributes());
                Object vertical = attrs.get("vertical");
                if (vertical != null && master.item(field.getListCode(), vertical.toString()) != null) {
                    return vertical.toString();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(KycSection section, Map<String, Object> doc) {
        Object v = doc.get(section.getKey());
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Map<String, Object> out = new LinkedHashMap<>();
        doc.put(section.getKey(), out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Map<String, Object> doc, String key) {
        Object v = doc.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object item : l) {
                if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private boolean itemsExist(Map<String, Object> doc, String key) {
        Object v = doc.get(key);
        return v instanceof List<?> l && !l.isEmpty();
    }

    private int coalesceMin(KycSection section) {
        return section.getMinItems() == null ? 0 : section.getMinItems();
    }

    private String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private record KycRuleBundle(String code, String validatorKey, Map<String, Object> params) { }

    private List<KycRuleBundle> rulesOf(List<com.mipay.sme.domain.kyc.KycRule> rules) {
        return rules.stream()
                .map(r -> new KycRuleBundle(r.getCode(), r.getValidatorKey(), KycJson.parseMap(r.getParams())))
                .toList();
    }
}
