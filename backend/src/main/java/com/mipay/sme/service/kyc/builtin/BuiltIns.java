package com.mipay.sme.service.kyc.builtin;

import com.mipay.sme.domain.kyc.KycListItem;
import com.mipay.sme.service.kyc.KycFacts;
import com.mipay.sme.service.kyc.KycJson;
import com.mipay.sme.service.kyc.KycMasterService;
import com.mipay.sme.service.kyc.KycPluginContracts;
import com.mipay.sme.service.kyc.KycPluginContracts.NormalizedDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The five built-in plugins of the standard plan (§2). */
public final class BuiltIns {

    private BuiltIns() { }

    /**
     * `high-risk-or-pep`: runs when the risk is HIGH, any person is a PIP, or any
     * check returned a HIT. Adds a compliance-review stage (COMPLIANCE_OFFICER)
     * and then senior-management-approval (SENIOR_APPROVER) — FIA s.23(2)(a), §5.3.
     */
    @Component
    public static class HighRiskOrPepContributor implements KycPluginContracts.KycStageContributor {

        @Override
        public String key() {
            return "high-risk-or-pep";
        }

        @Override
        public List<KycPluginContracts.StageDef> extraStages(
                KycPluginContracts.KycWorkflow.WorkflowContext ctx) {
            List<KycPluginContracts.StageDef> out = new ArrayList<>();
            if (triggers(ctx)) {
                out.add(new KycPluginContracts.StageDef("compliance-review", 99,
                        new KycPluginContracts.ActorRule(KycPluginContracts.Relation.PLATFORM,
                                null, "COMPLIANCE_OFFICER"),
                        List.of("APPROVE", "RETURN", "REJECT", "BLOCK"),
                        KycPluginContracts.OnReturn.PREVIOUS_STAGE));
                out.add(new KycPluginContracts.StageDef("senior-management-approval", 100,
                        new KycPluginContracts.ActorRule(KycPluginContracts.Relation.PLATFORM,
                                null, "SENIOR_APPROVER"),
                        List.of("APPROVE", "REJECT"),
                        KycPluginContracts.OnReturn.DRAFT));
            }
            return out;
        }

        private boolean triggers(KycPluginContracts.KycWorkflow.WorkflowContext ctx) {
            KycFacts facts = ctx.facts();
            Map<String, String> checks = ctx.checkResults() == null ? Map.of() : ctx.checkResults();
            return (facts != null && facts.anyPip())
                    || checks.values().stream().anyMatch(v -> "HIT".equalsIgnoreCase(v))
                    || (ctx.riskRating() != null && "HIGH".equalsIgnoreCase(ctx.riskRating()));
        }
    }

    /** `native-form`: the application collects data itself (§7). */
    @Component
    public static class NativeFormProvider implements KycPluginContracts.KycCollectionProvider {

        @Override
        public String key() {
            return "native-form";
        }

        @Override
        public Start start(KycPluginContracts.KycApplicationRef app) {
            return new Start(CollectMode.IN_APP, null);
        }

        @Override
        public NormalizedDocument onEvent(KycPluginContracts.ProviderEvent event) {
            return new NormalizedDocument(Map.of());
        }
    }

    /** `simulated`: current behaviour — checks complete synchronously and are CLEAR (§7). */
    @Component
    public static class SimulatedCheckProvider implements KycPluginContracts.KycCheckProvider {

        @Override
        public String key() {
            return "simulated";
        }

        @Override
        public boolean supports(String type, String country) {
            return true;
        }

        @Override
        public CheckResult request(CheckRequest req) {
            return new CheckResult(Status.CLEAR, "simulated " + req.checkType() + ": clear");
        }

        @Override
        public Set<String> allowedData() {
            // identity data only; never payment detail
            return Set.of("LEGAL_NAME", "UBO_NAME", "DOB", "UBO_ID_NUMBER", "UBO_DOB",
                    "UBO_NATIONALITY", "MCC", "REG_NUMBER");
        }
    }

    /** `manual`: operations record the result in the portal (§7). */
    @Component
    public static class ManualCheckProvider implements KycPluginContracts.KycCheckProvider {

        @Override
        public String key() {
            return "manual";
        }

        @Override
        public boolean supports(String type, String country) {
            return true;
        }

        @Override
        public CheckResult request(CheckRequest req) {
            return new CheckResult(Status.PENDING, "awaiting manual capture");
        }

        @Override
        public Set<String> allowedData() {
            return Set.of();
        }
    }

    /** `default`: today's applyRiskRating logic extended with MCC, volume and PIP factors. */
    @Component
    public static class DefaultKycRiskScorer implements KycPluginContracts.KycRiskScorer {

        @Autowired
        @Lazy
        private KycMasterService master;

        @Override
        public String key() {
            return "default";
        }

        @Override
        public RiskScore score(KycPluginContracts.KycWorkflow.WorkflowContext ctx) {
            KycFacts facts = ctx.facts();
            List<String> factors = new ArrayList<>();
            String risk = "LOW";
            if (facts == null) {
                return new RiskScore(risk, null, factors);
            }

            Object volumeBand = volumeBand(facts);
            String bandRisk = attributeRisk("NA_VOLUME_BAND", volumeBand, null);
            if (bandRisk != null) {
                risk = escalate(risk, bandRisk);
                factors.add("volume band " + volumeBand + " (" + bandRisk.toLowerCase() + " risk)");
            }

            String mcc = facts.tag("MCC").orElse(null);
            String mccRisk = attributeRisk("MCC", mcc, null);
            if (mccRisk != null) {
                risk = escalate(risk, mccRisk);
                factors.add("MCC " + mcc + " (" + mccRisk.toLowerCase() + " risk)");
            }

            if (facts.anyPip()) {
                risk = "HIGH";
                factors.add("a prominent influential person is declared");
            }

            int months = switch (risk) {
                case "HIGH" -> 12;
                case "MEDIUM" -> 24;
                default -> 36;
            };
            return new RiskScore(risk, Instant.now().plusSeconds(months * 30L * 24 * 3600), factors);
        }

        private Object volumeBand(KycFacts facts) {
            return facts.doc().get("operationalDetails") instanceof Map<?, ?> od
                    ? od.get("expectedMonthlyVolume") : null;
        }

        /** The risk attribute sits on the list item (§3.4). */
        private String attributeRisk(String listCode, Object value, String fallback) {
            if (value == null) return fallback;
            KycListItem item = master.item(listCode, value.toString());
            if (item == null || item.getAttributes() == null) return fallback;
            Map<String, Object> attrs = KycJson.parseMap(item.getAttributes());
            return attrs.get("risk") == null ? fallback : attrs.get("risk").toString();
        }

        private String escalate(String current, String candidate) {
            if ("HIGH".equals(candidate) || "HIGH".equals(current)) return "HIGH";
            if ("MEDIUM".equals(candidate)) return "MEDIUM";
            return current;
        }
    }
}
