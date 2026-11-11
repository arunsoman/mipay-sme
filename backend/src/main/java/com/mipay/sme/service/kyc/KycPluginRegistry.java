package com.mipay.sme.service.kyc;

import com.mipay.sme.domain.kyc.KycPlugin;
import com.mipay.sme.repository.KycPluginRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers the built-in KYC plugin beans at boot, publishes them into the
 * kyc_workflow_plugins master table (for observability) and exposes them by
 * key. The master table names built-ins by these keys (§2).
 */
@Component
@Slf4j
public class KycPluginRegistry {

    private final Map<String, KycPluginContracts.KycWorkflow> workflows = new HashMap<>();
    private final Map<String, KycPluginContracts.KycStageContributor> contributors = new HashMap<>();
    private final Map<String, KycPluginContracts.KycCollectionProvider> collectionProviders = new HashMap<>();
    private final Map<String, KycPluginContracts.KycCheckProvider> checkProviders = new HashMap<>();
    private final Map<String, KycPluginContracts.KycRiskScorer> riskScorers = new HashMap<>();
    private final Map<String, KycRuleValidator> ruleValidators = new HashMap<>();

    private final KycPluginRepository pluginRepo;

    public KycPluginRegistry(List<KycPluginContracts.KycWorkflow> workflowBeans,
                             List<KycPluginContracts.KycStageContributor> contributorBeans,
                             List<KycPluginContracts.KycCollectionProvider> collectionBeans,
                             List<KycPluginContracts.KycCheckProvider> checkBeans,
                             List<KycPluginContracts.KycRiskScorer> scorerBeans,
                             List<KycRuleValidator> validatorBeans,
                             KycPluginRepository pluginRepo,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.pluginRepo = pluginRepo;
        KycJson.setMapper(objectMapper);   // static mapper for the master/engine (§3.5)
        workflowBeans.forEach(b -> workflows.put(b.key(), b));
        contributorBeans.forEach(b -> contributors.put(b.key(), b));
        collectionBeans.forEach(b -> collectionProviders.put(b.key(), b));
        checkBeans.forEach(b -> checkProviders.put(b.key(), b));
        scorerBeans.forEach(b -> riskScorers.put(b.key(), b));
        validatorBeans.forEach(b -> ruleValidators.put(b.key(), b));
        log.info("KYC plugins: {} workflows, {} contributors, {} collection, {} check providers, "
                        + "{} scorers, {} rule validators",
                workflows.size(), contributors.size(), collectionProviders.size(),
                checkProviders.size(), riskScorers.size(), ruleValidators.size());
    }

    @Transactional
    public void publish() {
        // batch delete runs immediately; a plain deleteAll lets Hibernate order
        // inserts-before-deletes inside the flush and hit uq_kyc_plugin on restart
        pluginRepo.deleteAllInBatch();
        pluginRepo.saveAll(registry(KycPlugin.Interfaces.WORKFLOW, workflows));
        pluginRepo.saveAll(registry(KycPlugin.Interfaces.STAGE_CONTRIBUTOR, contributors));
        pluginRepo.saveAll(registry(KycPlugin.Interfaces.COLLECTION_PROVIDER, collectionProviders));
        pluginRepo.saveAll(registry(KycPlugin.Interfaces.CHECK_PROVIDER, checkProviders));
        pluginRepo.saveAll(registry(KycPlugin.Interfaces.RISK_SCORER, riskScorers));
        pluginRepo.saveAll(registry(KycPlugin.Interfaces.RULE_VALIDATOR, ruleValidators));
    }

    private <T> List<KycPlugin> registry(String interfaceKey, Map<String, T> beans) {
        return beans.entrySet().stream().map(e -> {
            KycPlugin p = new KycPlugin();
            p.setInterfaceKey(interfaceKey);
            p.setPluginKey(e.getKey());
            p.setBeanName(e.getValue().getClass().getSimpleName());
            return p;
        }).toList();
    }

    public KycPluginContracts.KycWorkflow workflow(String key) {
        KycPluginContracts.KycWorkflow w = workflows.get(key);
        if (w == null) throw new IllegalArgumentException("Unknown workflow plugin '" + key + "'");
        return w;
    }

    public Map<String, KycPluginContracts.KycWorkflow> workflows() {
        return workflows;
    }

    public KycPluginContracts.KycStageContributor contributor(String key) {
        KycPluginContracts.KycStageContributor c = contributors.get(key);
        if (c == null) throw new IllegalArgumentException("Unknown stage-contributor plugin '" + key + "'");
        return c;
    }

    public KycPluginContracts.KycCollectionProvider collectionProvider(String key) {
        KycPluginContracts.KycCollectionProvider p = collectionProviders.get(key);
        if (p == null) throw new IllegalArgumentException("Unknown collection provider '" + key + "'");
        return p;
    }

    public KycPluginContracts.KycCheckProvider checkProvider(String key) {
        KycPluginContracts.KycCheckProvider p = checkProviders.get(key);
        if (p == null) throw new IllegalArgumentException("Unknown check provider '" + key + "'");
        return p;
    }

    public KycPluginContracts.KycRiskScorer riskScorer(String key) {
        KycPluginContracts.KycRiskScorer s = riskScorers.get(key);
        if (s == null) throw new IllegalArgumentException("Unknown risk scorer '" + key + "'");
        return s;
    }

    public KycRuleValidator ruleValidator(String key) {
        KycRuleValidator v = ruleValidators.get(key);
        if (v == null) throw new IllegalArgumentException("Unknown rule validator '" + key + "'");
        return v;
    }

    public Map<String, KycRuleValidator> ruleValidators() {
        return ruleValidators;
    }
}
