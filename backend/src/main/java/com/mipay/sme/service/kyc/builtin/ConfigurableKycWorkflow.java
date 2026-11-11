package com.mipay.sme.service.kyc.builtin;

import com.mipay.sme.service.kyc.KycFacts;
import com.mipay.sme.service.kyc.KycJson;
import com.mipay.sme.service.kyc.KycMasterService;
import com.mipay.sme.service.kyc.KycPluginContracts;
import com.mipay.sme.service.kyc.KycPluginRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The `configurable` built-in workflow (§5.3, §11): reads the profile's
 * workflow_config — approvalRequired, entryCheck, verification, stages — and
 * gives stage contributors a chance to add stages (plans only grow).
 */
@Component
public class ConfigurableKycWorkflow implements KycPluginContracts.KycWorkflow {

    @Autowired
    private KycMasterService master;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private KycPluginRegistry registry;

    @Override
    public String key() {
        return "configurable";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public KycPluginContracts.WorkflowPlan plan(KycPluginContracts.KycWorkflow.WorkflowContext ctx) {
        KycMasterService.ProfileBundle bundle = master.bySchemaVersion(ctx.schemaVersionId());
        Map<String, Object> config = KycJson.parseMap(bundle.profile().getWorkflowConfig());

        boolean approvalRequired = Boolean.TRUE.equals(config.getOrDefault("approvalRequired", true));
        KycPluginContracts.EntryCheck entryCheck = KycPluginContracts.EntryCheck.valueOf(
                String.valueOf(config.getOrDefault("entryCheck", "NONE")));
        List<String> verification = new ArrayList<>();
        if (config.get("verification") instanceof List<?> v) {
            v.forEach(x -> verification.add(String.valueOf(x)));
        }

        List<KycPluginContracts.StageDef> stages = new ArrayList<>();
        int seq = 0;
        if (config.get("stages") instanceof List<?> stageList) {
            for (Object s : stageList) {
                if (s instanceof Map<?, ?> m) {
                    KycPluginContracts.ActorRule actor =
                            actorOf(cast(m.get("actor")));
                    List<String> outcomes = new ArrayList<>();
                    if (m.get("outcomes") instanceof List<?> o) {
                        o.forEach(x -> outcomes.add(String.valueOf(x)));
                    }
                    if (outcomes.isEmpty()) outcomes.addAll(List.of("APPROVE", "RETURN", "REJECT", "BLOCK"));
                    stages.add(new KycPluginContracts.StageDef(String.valueOf(m.get("key")), ++seq,
                            actor, outcomes, KycPluginContracts.OnReturn.DRAFT));
                }
            }
        }

        // contributors may ADD stages, never remove (§6.2)
        if (config.get("contributors") instanceof List<?> list) {
            for (Object c : list) {
                try {
                    KycPluginContracts.KycStageContributor contributor = registry.contributor(String.valueOf(c));
                    for (KycPluginContracts.StageDef extra : contributor.extraStages(ctx)) {
                        KycPluginContracts.ActorRule actor = extra.actor();
                        List<String> outcomes = extra.outcomes();
                        stages.add(new KycPluginContracts.StageDef(extra.key(), ++seq,
                                actor, outcomes, extra.onReturn()));
                    }
                } catch (IllegalArgumentException ignored) {
                    // unknown contributor keys fail the boot master validator; ignore at plan time
                }
            }
        }
        return new KycPluginContracts.WorkflowPlan(approvalRequired, entryCheck, verification, stages);
    }

    private KycPluginContracts.ActorRule actorOf(Map<String, Object> m) {
        if (m == null) {
            return new KycPluginContracts.ActorRule(KycPluginContracts.Relation.PLATFORM,
                    null, null);
        }
        return KycPluginContracts.ActorRule.from(m);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }
}
