package com.mipay.sme.service.kyc;

import com.mipay.sme.domain.kyc.KycProfile;
import com.mipay.sme.domain.kyc.KycSchemaVersion;
import com.mipay.sme.domain.kyc.KycSection;
import com.mipay.sme.domain.kyc.KycRule;
import com.mipay.sme.service.kyc.KycMasterService.ProfileBundle;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Everything a rule validator and the field validator need while checking one
 * document: schema access, the profile's params, condition evaluation and the
 * problem collector. Problems carry a severity (§3.3: warning or error).
 */
public class KycValidationContext {

    public record Problem(String path, String fieldPath, String message, KycRule.Severity severity) {
        public boolean isError() { return severity == KycRule.Severity.ERROR; }
    }

    public final ProfileBundle bundle;
    public final KycFacts facts;
    public final Map<String, Object> doc;
    public final Map<String, Object> context;
    public final KycRuleValidators.KycUniqueChecker uniqueChecker;

    private final Set<Problem> problems = new LinkedHashSet<>();

    public KycValidationContext(ProfileBundle bundle, KycFacts facts,
                                Map<String, Object> doc, Map<String, Object> context) {
        this.bundle = bundle;
        this.facts = facts;
        this.doc = doc;
        this.context = context;
        this.uniqueChecker = null;
    }

    public KycValidationContext(ProfileBundle bundle, KycFacts facts,
                                Map<String, Object> doc, Map<String, Object> context,
                                KycRuleValidators.KycUniqueChecker uniqueChecker) {
        this.bundle = bundle;
        this.facts = facts;
        this.doc = doc;
        this.context = context;
        this.uniqueChecker = uniqueChecker;
    }

    // ---------------------------------------------------------------- problems

    public void error(String fieldPath, String message) {
        add(new Problem(fieldPath, fieldPath, message, KycRule.Severity.ERROR));
    }

    public void warning(String fieldPath, String message) {
        add(new Problem(fieldPath, fieldPath, message, KycRule.Severity.WARNING));
    }

    public void add(Problem p) {
        problems.add(p);
    }

    public boolean hasErrors() {
        return problems.stream().anyMatch(Problem::isError);
    }

    public List<Problem> all() {
        return new ArrayList<>(problems);
    }

    // ---------------------------------------------------------------- helpers

    public KycSchemaVersion schema() {
        return bundle.schema();
    }

    public KycProfile profile() {
        return bundle.profile();
    }

    public List<KycSection> sections() {
        return bundle.sections();
    }

    public List<KycSection> visibleSections() {
        return bundle.sections().stream()
                .filter(s -> KycConditionEvaluator.evaluate(parse(s.getVisibleWhen()), doc, null, context))
                .toList();
    }

    public Object parse(String json) {
        return KycJson.parse(json);
    }

    /** Resolves "@"-parameter references against the profile params (@uboThresholdPct). */
    public Object param(String key) {
        Object v = bundle.param(key);
        if (v instanceof String s && s.startsWith("@")) {
            return bundle.param(s.substring(1));
        }
        return v;
    }

    public int intParam(String key, int fallback) {
        Object v = param(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public String strParam(String key, String fallback) {
        Object v = param(key);
        return v == null ? fallback : v.toString();
    }

    public void eachVisibleItem(String section, BiPredicate<Map<String, Object>, Integer> fn) {
        List<Map<String, Object>> items = facts.list(section);
        for (int i = 0; i < items.size(); i++) {
            if (!fn.test(items.get(i), i)) return;
        }
    }
}
