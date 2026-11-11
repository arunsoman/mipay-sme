package com.mipay.sme.service.kyc;

import com.mipay.sme.domain.kyc.KycField.DataType;
import com.mipay.sme.domain.kyc.KycListItem;
import com.mipay.sme.domain.kyc.KycProfile;
import com.mipay.sme.domain.kyc.KycRule;
import com.mipay.sme.domain.kyc.KycSchemaVersion;
import com.mipay.sme.domain.kyc.KycSection;
import com.mipay.sme.service.kyc.KycMasterService.ProfileBundle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boot-time master validation (§2): every published profile must resolve to a
 * real workflow, providers, scorer and rule keys, every list_code must exist,
 * every condition must refer to a real field, and profile matching must be
 * unambiguous. On any failure the app refuses to start.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KycMasterValidator {

    private final KycMasterService master;
    private final KycPluginRegistry plugins;

    private List<String> lastProblems = List.of();

    public List<String> lastProblems() {
        return lastProblems;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onReady() {
        validate();
    }

    /** Validates the whole master; collects every problem, throws if any. */
    public void validate() {
        plugins.publish();
        List<String> problems = new ArrayList<>();

        // every active profile must bundle cleanly and reference resolvable keys
        for (KycProfile profile : activeProfiles()) {
            ProfileBundle bundle;
            try {
                bundle = master.resolve(profile.getCountry(), licence(profile), subject(profile),
                        profile.getEntityType(), profile.getOrgType());
            } catch (Exception e) {
                problems.add("profile " + profile.getCode() + ": " + e.getMessage());
                continue;
            }
            validateProfile(profile, bundle, problems);
        }

        // rule validators must all exist for every saved row
        for (KycProfile p : activeProfiles()) {
            try {
                ProfileBundle bundle = master.bundleOf(p);
                for (KycRule rule : bundle.saveRules()) {
                    checkValidator(p, rule, problems);
                }
                for (KycRule rule : bundle.submitRules()) {
                    checkValidator(p, rule, problems);
                }
            } catch (Exception e) {
                problems.add("profile " + p.getCode() + ": " + e.getMessage());
            }
        }

        lastProblems = problems;
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Broken KYC master: "
                    + String.join("; ", problems));
        }
        log.info("KYC master validated: all active profiles resolve cleanly");
    }

    private void checkValidator(KycProfile p, KycRule rule, List<String> problems) {
        if (!plugins.ruleValidators().containsKey(rule.getValidatorKey())) {
            problems.add("profile " + p.getCode() + ": unknown validator '"
                    + rule.getValidatorKey() + "' (rule " + rule.getCode() + ")");
        }
    }

    private void validateProfile(KycProfile profile, ProfileBundle bundle, List<String> problems) {
        String ref = "profile " + profile.getCode();

        // workflow key resolves
        try {
            plugins.workflow(profile.getWorkflowKey());
        } catch (IllegalArgumentException e) {
            problems.add(ref + ": " + e.getMessage());
        }

        // collection provider resolves
        try {
            plugins.collectionProvider(profile.getCollectionProvider());
        } catch (IllegalArgumentException e) {
            problems.add(ref + ": " + e.getMessage());
        }

        // check providers resolve and support their type for the profile country
        Map<String, Object> checkProvidersJson = KycJson.parseMap(profile.getCheckProviders());
        for (Map.Entry<String, Object> e : checkProvidersJson.entrySet()) {
            String checkType = String.valueOf(e.getKey());
            if (!KycPluginContracts.CHECK_TYPES.contains(checkType)) {
                problems.add(ref + ": unknown check type '" + checkType + "'");
                continue;
            }
            String provider = String.valueOf(e.getValue());
            try {
                KycPluginContracts.KycCheckProvider p = plugins.checkProvider(provider);
                if (!p.supports(checkType, profile.getCountry())) {
                    problems.add(ref + ": provider " + provider + " does not support " + checkType);
                }
            } catch (IllegalArgumentException ex) {
                problems.add(ref + ": " + ex.getMessage());
            }
        }

        // risk scorer resolves
        try {
            plugins.riskScorer(profile.getRiskScorer());
        } catch (IllegalArgumentException e) {
            problems.add(ref + ": " + e.getMessage());
        }

        // contributors in the workflow config resolve
        Map<String, Object> wfConfig = KycJson.parseMap(profile.getWorkflowConfig());
        Object contributors = wfConfig.get("contributors");
        if (contributors instanceof List<?> list) {
            for (Object key : list) {
                try {
                    plugins.contributor(String.valueOf(key));
                } catch (IllegalArgumentException e) {
                    problems.add(ref + ": " + e.getMessage());
                }
            }
        }

        // stage actors carry either a platform function or an org role
        Object stages = wfConfig.get("stages");
        if (stages instanceof List<?> list) {
            for (Object s : list) {
                if (s instanceof Map<?, ?> stage) {
                    Object actor = stage.get("actor");
                    if (actor instanceof Map<?, ?> a) {
                        Object rel = a.get("relation");
                        String relation = rel == null ? "" : rel.toString();
                        String function = a.get("function") == null ? null : a.get("function").toString();
                        String orgRole = a.get("orgRole") == null ? null : a.get("orgRole").toString();
                        if (relation.equals("PLATFORM") && (function == null || function.isBlank())) {
                            problems.add(ref + ": PLATFORM stage '" + stage.get("key") + "' needs a function");
                        }
                        if (!relation.equals("PLATFORM") && relation.startsWith("SELF")
                                && (orgRole == null || orgRole.isBlank())) {
                            problems.add(ref + ": org stage '" + stage.get("key") + "' needs an org role");
                        }
                    }
                }
            }
        }

        // every ENUM/MULTI_ENUM/COUNTRY field has a list with at least one active item
        for (KycSection section : bundle.sections()) {
            for (com.mipay.sme.domain.kyc.KycField field : bundle.sectionFields(section.getKey())) {
                if (needsList(field.getDataType())) {
                    if (field.getListCode() == null) {
                        problems.add(ref + ": field " + section.getKey() + "." + field.getKey()
                                + " needs a list_code");
                    } else {
                        List<KycListItem> items = master.listItems(field.getListCode(), null);
                        if (items.isEmpty()) {
                            problems.add(ref + ": list '" + field.getListCode() + "' has no active items");
                        }
                    }
                }
                // conditions must parse (they are parsed lazily; malformed JSON is null)
                if (field.getRequiredWhen() != null
                        && field.getRequired() == com.mipay.sme.domain.kyc.KycField.Required.WHEN
                        && KycJson.parse(field.getRequiredWhen()) == null) {
                    problems.add(ref + ": field " + section.getKey() + "." + field.getKey()
                            + " has invalid required_when JSON");
                }
                if (field.getVisibleWhen() != null && KycJson.parse(field.getVisibleWhen()) == null) {
                    problems.add(ref + ": field " + section.getKey() + "." + field.getKey()
                            + " has invalid visible_when JSON");
                }
            }
        }
    }

    private List<KycProfile> activeProfiles() {
        return master.profilesForValidation();
    }

    private String licence(KycProfile p) {
        return p.getLicenceType();
    }

    private String subject(KycProfile p) {
        return p.getSubjectType();
    }

    private String name(Object enumValue) {
        return String.valueOf(enumValue);
    }

    private boolean needsList(DataType type) {
        return type == DataType.ENUM || type == DataType.MULTI_ENUM || type == DataType.COUNTRY;
    }
}
