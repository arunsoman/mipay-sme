package com.mipay.sme.service.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.kyc.KycField;
import com.mipay.sme.domain.kyc.KycListItem;
import com.mipay.sme.domain.kyc.KycProfile;
import com.mipay.sme.domain.kyc.KycRule;
import com.mipay.sme.domain.kyc.KycSchemaVersion;
import com.mipay.sme.domain.kyc.KycSection;
import com.mipay.sme.repository.KycFieldRepository;
import com.mipay.sme.repository.KycListItemRepository;
import com.mipay.sme.repository.KycProfileRepository;
import com.mipay.sme.repository.KycRuleRepository;
import com.mipay.sme.repository.KycSchemaVersionRepository;
import com.mipay.sme.repository.KycSectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the KYC master (§3) behind an in-memory cache — the master changes
 * only on deploy, so the cache cannot go stale (D9: 50-80 orgs/month needs
 * nothing stronger). One profile per (country, licence, subject) plus optional
 * entity/org refinements; the most specific active match wins.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KycMasterService {

    private final KycProfileRepository profileRepo;
    private final KycSchemaVersionRepository schemaRepo;
    private final KycSectionRepository sectionRepo;
    private final KycFieldRepository fieldRepo;
    private final KycRuleRepository ruleRepo;
    private final KycListItemRepository listItemRepo;
    private final ObjectMapper objectMapper;

    private final Map<String, ProfileBundle> profileCache = new ConcurrentHashMap<>();
    private final Map<String, List<KycListItem>> listCache = new ConcurrentHashMap<>();

    public record ProfileBundle(KycProfile profile, KycSchemaVersion schema, List<KycSection> sections,
                                Map<String, List<KycField>> fieldsBySection, List<KycRule> saveRules,
                                List<KycRule> submitRules, Map<String, Object> params) {

        public List<KycField> sectionFields(String sectionKey) {
            return fieldsBySection.getOrDefault(sectionKey, List.of());
        }

        public Map<String, KycField> allFieldsBySectionKey() {
            Map<String, KycField> out = new LinkedHashMap<>();
            fieldsBySection.forEach((k, v) -> v.forEach(f -> out.put(k + "." + f.getKey(), f)));
            return out;
        }

        public Object param(String key) {
            return params.get(key);
        }

        public int intParam(String key, int fallback) {
            Object v = params.get(key);
            return v instanceof Number n ? n.intValue() : fallback;
        }
    }

    // ---------------------------------------------------------------- resolution

    @Transactional(readOnly = true)
    public ProfileBundle resolve(String country, String licenceType, String subjectType,
                                 String entityType, String orgType) {
        String code = country + "/" + licenceType + "/" + subjectType + "/" + orgType + "/" + entityType;
        ProfileBundle cached = profileCache.get(code);
        if (cached != null) return cached;

        List<KycProfile> candidates = profileRepo.findByCountryAndLicenceTypeAndSubjectTypeAndActiveTrue(
                country, licenceType, subjectType);
        List<KycProfile> matching = candidates.stream()
                .filter(p -> p.getEntityType() == null || (entityType != null && p.getEntityType().equals(entityType)))
                .filter(p -> p.getOrgType() == null || (orgType != null && p.getOrgType().equals(orgType)))
                .toList();
        int best = matching.stream().mapToInt(this::specificity).max().orElse(-1);
        if (best < 0) {
            throw new NotFoundException(
                    "No active KYC profile for " + code + " (ui_enabled profiles only are offered on screen)");
        }
        List<KycProfile> winners = matching.stream().filter(p -> specificity(p) == best).toList();
        if (winners.size() > 1) {
            throw new ConflictException("KYC_PROFILE_AMBIGUOUS",
                    "More than one active KYC profile matches " + code);
        }
        ProfileBundle bundle = bundleOf(winners.get(0));
        profileCache.put(code, bundle);
        return bundle;
    }

    private int specificity(KycProfile p) {
        return (p.getEntityType() != null ? 2 : 0) + (p.getOrgType() != null ? 2 : 0);
    }

    /** Convenience: the single-country PSP platform default. */
    @Transactional(readOnly = true)
    public ProfileBundle resolveByOrg(String orgType) {
        return resolve("NA", "PSP", "BUSINESS", null, orgType);
    }

    /** Core resolves with country and optionally the declared entity type. */
    @Transactional(readOnly = true)
    public ProfileBundle resolveByOrg(String orgType, String country, String entityType) {
        return resolve(country == null || country.isBlank() ? "NA" : country, "PSP", "BUSINESS",
                entityType, orgType);
    }

    /** Full resolution: country + licence type select the profile (§9). */
    @Transactional(readOnly = true)
    public ProfileBundle resolveByOrg(String orgType, String country, String licenceType, String entityType) {
        return resolve(country == null || country.isBlank() ? "NA" : country,
                licenceType == null || licenceType.isBlank() ? "PSP" : licenceType, "BUSINESS",
                entityType, orgType);
    }

    @Transactional(readOnly = true)
    public ProfileBundle bundleOf(KycProfile profile) {
        KycSchemaVersion schema = schemaRepo.findById(profile.getSchemaVersionId())
                .orElseThrow(() -> new ConflictException("KYC_MASTER_BROKEN",
                        "Schema version " + profile.getSchemaVersionId() + " does not exist"));
        return bundleOf(profile, schema);
    }

    /** The profile's workflow and params over the given schema version's sections, fields and rules. */
    private ProfileBundle bundleOf(KycProfile profile, KycSchemaVersion schema) {
        List<KycSection> sections = sectionRepo.findBySchemaVersionIdOrderBySortAsc(schema.getId());
        Map<String, List<KycField>> fields = new LinkedHashMap<>();
        for (KycSection section : sections) {
            fields.put(section.getKey(), fieldRepo.findBySectionIdOrderBySortAsc(section.getId()));
        }
        Map<String, Object> params = readJson(profile.getParams(), Map.class, "params");
        return new ProfileBundle(profile, schema, sections, fields,
                ruleRepo.findBySchemaVersionIdAndPhase(schema.getId(), KycRule.Phase.SAVE),
                ruleRepo.findBySchemaVersionIdAndPhase(schema.getId(), KycRule.Phase.SUBMIT),
                params == null ? Map.of() : params);
    }

    @Transactional(readOnly = true)
    public ProfileBundle bySchemaVersion(String schemaVersionId) {
        String key = "bySchema:" + schemaVersionId;
        ProfileBundle cached = profileCache.get(key);
        if (cached != null) return cached;
        KycSchemaVersion schema = schemaRepo.findById(schemaVersionId)
                .orElseThrow(() -> new NotFoundException("Schema version not found"));
        List<KycProfile> active = profileRepo.findByActiveTrue();
        List<KycProfile> profiles = active.stream()
                .filter(p -> schemaVersionId.equals(p.getSchemaVersionId()))
                .toList();
        ProfileBundle bundle;
        if (!profiles.isEmpty()) {
            bundle = bundleOf(profiles.get(0));
        } else {
            // A superseded version: applications pinned to it keep its form and rules, and run
            // under the workflow of the profile that moved on to the successor of the same code.
            KycProfile successor = active.stream()
                    .filter(p -> schemaRepo.findById(p.getSchemaVersionId())
                            .map(v -> v.getCode().equals(schema.getCode())).orElse(false))
                    .findFirst().orElse(active.isEmpty() ? null : active.get(0));
            if (successor == null) throw new NotFoundException("No profile uses this schema version");
            bundle = bundleOf(successor, schema);
        }
        profileCache.put(key, bundle);
        return bundle;
    }

    // ---------------------------------------------------------------- lists

    @Transactional(readOnly = true)
    public List<KycListItem> listItems(String listCode, String parentValue) {
        String key = listCode + ":" + (parentValue == null ? "" : parentValue);
        List<KycListItem> cached = listCache.get(key);
        if (cached != null) return cached;
        List<KycListItem> items = parentValue == null || parentValue.isBlank()
                ? listItemRepo.findByListCodeAndActiveTrueOrderBySortAsc(listCode)
                : listItemRepo.findByListCodeAndParentValueAndActiveTrueOrderBySortAsc(listCode, parentValue);
        listCache.put(key, items);
        return items;
    }

    /** True when the value is an active item of the list (parent-filtered when given). */
    @Transactional(readOnly = true)
    public boolean itemValid(String listCode, String parentValue, String value) {
        if (value == null) return false;
        return listItems(listCode, parentValue).stream().anyMatch(i -> i.getValue().equals(value));
    }

    public KycListItem item(String listCode, String value) {
        return listItems(listCode, null).stream()
                .filter(i -> i.getValue().equals(value)).findFirst().orElse(null);
    }

    public void evictAll() {
        profileCache.clear();
        listCache.clear();
    }

    @Transactional(readOnly = true)
    public List<KycProfile> profilesForValidation() {
        return profileRepo.findByActiveTrue();
    }

    private <T> T readJson(String json, Class<T> type, String what) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new ConflictException("KYC_MASTER_BROKEN", what + " is not valid JSON");
        }
    }
}
