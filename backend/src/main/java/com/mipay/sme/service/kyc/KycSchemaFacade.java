package com.mipay.sme.service.kyc;

import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.kyc.KycField;
import com.mipay.sme.domain.kyc.KycListItem;
import com.mipay.sme.domain.kyc.KycSection;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.service.OrgCallerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read models for the screens (§12): GET /api/kyc/schema returns the resolved
 * profile with sections, fields, conditions, rules and small lists inline;
 * GET /api/kyc/lists/{code} serves the large lists with paging.
 */
@Service
@RequiredArgsConstructor
public class KycSchemaFacade {

    private final KycMasterService master;
    private final OrganizationRepository orgRepo;
    private final OrgCallerService callerService;

    @Transactional(readOnly = true)
    public Map<String, Object> schemaFor(String orgId, String entityType) {
        var org = orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Organization not found"));
        return schemaForType(org.getOrgType() == null ? "MERCHANT" : org.getOrgType().name(), org.getCountry(), entityType);
    }

    /** The form an org of this type fills in, before the org exists (the onboarding wizard). */
    @Transactional(readOnly = true)
    public Map<String, Object> schemaForType(String orgType, String country, String entityType) {
        KycMasterService.ProfileBundle bundle = master.resolveByOrg(orgType, country, entityType);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", Map.of(
                "code", bundle.profile().getCode(),
                "country", bundle.profile().getCountry(),
                "licenceType", bundle.profile().getLicenceType(),
                "uiEnabled", bundle.profile().isUiEnabled()));
        out.put("schema", Map.of(
                "id", bundle.schema().getId(),
                "code", bundle.schema().getCode(),
                "version", bundle.schema().getVersion()));

        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, Object> context = Map.of(
                "orgType", orgType == null ? "" : orgType,
                "country", country == null ? "NA" : country,
                "licenceType", "PSP");
        for (KycSection section : bundle.sections()) {
            boolean visible = KycConditionEvaluator.evaluate(KycJson.parse(section.getVisibleWhen()),
                    Map.of(), null, context);
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("key", section.getKey());
            sv.put("title", section.getTitle());
            sv.put("step", section.getStep());
            sv.put("kind", section.getKind().name());
            sv.put("minItems", section.getMinItems());
            sv.put("maxItems", section.getMaxItems());
            sv.put("visible", visible);
            sv.put("sort", section.getSort());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (KycField field : bundle.sectionFields(section.getKey())) {
                Map<String, Object> fv = new LinkedHashMap<>();
                fv.put("key", field.getKey());
                fv.put("label", field.getLabel());
                fv.put("helpText", field.getHelpText());
                fv.put("sort", field.getSort());
                fv.put("dataType", field.getDataType().name());
                fv.put("listCode", field.getListCode());
                fv.put("listParentField", field.getListParentField());
                fv.put("required", field.getRequired().name());
                fv.put("requiredWhen", KycJson.parse(field.getRequiredWhen()));
                fv.put("visibleWhen", KycJson.parse(field.getVisibleWhen()));
                fv.put("pattern", field.getPattern());
                fv.put("minLength", field.getMinLen());
                fv.put("maxLength", field.getMaxLen());
                fv.put("minValue", field.getMinValue());
                fv.put("maxValue", field.getMaxValue());
                fv.put("dateRule", field.getDateRule() == null ? "ANY" : field.getDateRule().name());
                fv.put("derivedFrom", field.getDerivedFrom());
                fv.put("canonicalTag", field.getCanonicalTag());
                fv.put("sensitivity", field.getSensitivity().name());
                fv.put("sensitive", field.getSensitivity() != KycField.Sensitivity.NONE);
                fields.add(fv);
            }
            sv.put("fields", fields);
            sections.add(sv);
        }
        out.put("sections", sections);

        // rules surface for the review-screen hints
        out.put("rules", bundle.saveRules().stream()
                .map(r -> Map.of("code", r.getCode(), "phase", r.getPhase().name(),
                        "validator", r.getValidatorKey(), "message", r.getMessage()))
                .toList());
        out.put("submitRules", bundle.submitRules().stream()
                .map(r -> Map.of("code", r.getCode(), "phase", r.getPhase().name(),
                        "validator", r.getValidatorKey(), "message", r.getMessage()))
                .toList());

        // small lists inline (§12)
        Map<String, Object> lists = new LinkedHashMap<>();
        for (String code : List.of("NA_ENTITY_TYPE", "ADDRESS_TYPE", "ID_TYPE", "OWNERSHIP_TYPE",
                "CONTROL_BASIS", "CURRENCY", "NA_ACCOUNT_TYPE", "NA_BANK")) {
            lists.put(code, items(code, null));
        }
        out.put("lists", lists);
        // tunables the form uses for hints (e.g. the 25% beneficial-owner threshold)
        out.put("params", bundle.params());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listItems(String code, String parent) {
        return listItems(code, parent, null, 0, 50);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listItems(String code, String parent, String q, int page, int size) {
        List<Map<String, Object>> rows = items(code, parent);
        if (q != null && !q.isBlank()) {
            String ql = q.toLowerCase();
            rows = rows.stream()
                    .filter(r -> String.valueOf(r.getOrDefault("value", "")).toLowerCase().contains(ql)
                            || String.valueOf(r.getOrDefault("label", "")).toLowerCase().contains(ql))
                    .toList();
        }
        int from = Math.min(page * size, rows.size());
        int to = Math.min(from + size, rows.size());
        List<Map<String, Object>> paged = rows.subList(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("parent", parent);
        out.put("q", q);
        out.put("page", page);
        out.put("size", size);
        out.put("count", rows.size());
        out.put("items", paged);
        return out;
    }

    private List<Map<String, Object>> items(String code, String parent) {
        List<KycListItem> rows = master.listItems(code, parent);
        return rows.stream()
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("value", i.getValue());
                    m.put("label", i.getLabel());
                    if (i.getParentValue() != null) m.put("parent", i.getParentValue());
                    if (i.getAttributes() != null) m.put("attributes", KycJson.parse(i.getAttributes()));
                    return m;
                })
                .toList();
    }
}
