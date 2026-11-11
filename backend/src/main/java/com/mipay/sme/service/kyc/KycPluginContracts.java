package com.mipay.sme.service.kyc;

import com.mipay.sme.service.kyc.KycFacts;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Workflow plugin contracts (§6.1). Every built-in is a Spring bean, named by key in the master. */
public final class KycPluginContracts {

    private KycPluginContracts() { }

    public enum EntryCheck { NONE, OPTIONAL, REQUIRED }
    public enum OnReturn { DRAFT, PREVIOUS_STAGE }
    public enum Relation { SELF_ORG, PARENT_ORG, ANCESTOR_ORG, PLATFORM }

    public interface CheckType { }
    public static final Set<String> CHECK_TYPES =
            Set.of("IDENTITY", "SANCTIONS", "PEP", "ADVERSE_MEDIA", "COMPANY_REGISTRY", "BANK_ACCOUNT");

    /** How the workflow answers "is approval needed, maker-checker at entry, which stages". */
    public interface KycWorkflow {
        String key();

        int version();

        WorkflowPlan plan(WorkflowContext ctx);

        /** The workflow receives facts, not country-specific JSON (§4). */
        record WorkflowContext(String applicationId, String orgId, String orgType, String entityType,
                               String country, String licenceType, String schemaVersionId, int submissionNo,
                               KycFacts facts, Map<String, String> checkResults, List<String> actedBy,
                               String riskRating) {

            public Map<String, Object> asContext() {
                Map<String, Object> context = new java.util.LinkedHashMap<>();
                context.put("orgType", orgType == null ? "" : orgType);
                return context;
            }
        }
    }

    /** Adds extra stages from the data, risk and check results; plans only grow (§6.2). */
    public interface KycStageContributor {
        String key();

        List<StageDef> extraStages(KycWorkflow.WorkflowContext ctx);
    }
    /** How the data is collected: the native form today, hosted providers later. */
    public interface KycCollectionProvider {
        String key();

        Start start(KycApplicationRef app);

        NormalizedDocument onEvent(ProviderEvent event);

        record Start(CollectMode mode, String url) { }
        enum CollectMode { IN_APP, REDIRECT }
    }

    /** Identity, sanctions, PEP, registry and bank-account checks. */
    public interface KycCheckProvider {
        String key();

        boolean supports(String type, String country);

        CheckResult request(CheckRequest req);

        Set<String> allowedData();

        record CheckRequest(String applicationId, int submissionNo, String subject, String checkType,
                            String country, Map<String, Object> facts) { }

        record CheckResult(Status status, String detail) { }

        enum Status { PENDING, CLEAR, HIT, ERROR }
    }

    /** Risk rating and review cycle. */
    public interface KycRiskScorer {
        String key();

        RiskScore score(KycWorkflow.WorkflowContext ctx);

        record RiskScore(String rating, java.time.Instant nextReviewAt, List<String> factors) { }
    }

    // ---------------------------------------------------------------- shapes

    public record WorkflowPlan(boolean approvalRequired, EntryCheck entryCheck,
                               List<String> verification, List<StageDef> stages) { }

    public record StageDef(String key, int seq, ActorRule actor, List<String> outcomes, OnReturn onReturn) { }

    public record ActorRule(Relation relation, String orgRole, String platformFunction) {
        public static ActorRule from(Map<String, Object> m) {
            Relation relation = Relation.valueOf(String.valueOf(m.getOrDefault("relation", "PLATFORM")));
            String orgRole = text(m.get("orgRole"));
            String function = text(m.get("function"));
            return new ActorRule(relation, orgRole, function);
        }

        private static String text(Object o) {
            return o == null || "null".equals(o.toString()) ? null : o.toString();
        }

        public Map<String, Object> toMap() {
            return new java.util.LinkedHashMap<>(Map.of("relation", relation.name(),
                    "orgRole", orgRole == null ? "" : orgRole,
                    "function", platformFunction == null ? "" : platformFunction));
        }
    }

    public record KycApplicationRef(String applicationId, String orgId, int submissionNo) { }

    public record ProviderEvent(String provider, String eventId, Map<String, Object> payload) { }

    public record NormalizedDocument(Map<String, Object> updates) { }
}
