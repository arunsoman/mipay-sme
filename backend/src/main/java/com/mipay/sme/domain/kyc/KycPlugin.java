package com.mipay.sme.domain.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Built-in plugin registry, written by KycPluginRegistry at boot (§2). */
@Getter
@Setter
@Entity
@Table(name = "kyc_workflow_plugins")
public class KycPlugin extends KycIdEntity {

    public interface Interfaces {
        String WORKFLOW = "WORKFLOW";
        String STAGE_CONTRIBUTOR = "STAGE_CONTRIBUTOR";
        String COLLECTION_PROVIDER = "COLLECTION_PROVIDER";
        String CHECK_PROVIDER = "CHECK_PROVIDER";
        String RISK_SCORER = "RISK_SCORER";
        String RULE_VALIDATOR = "RULE_VALIDATOR";
    }

    @Column(name = "interface_key", nullable = false, length = 64)
    private String interfaceKey;

    @Column(name = "plugin_key", nullable = false, length = 64)
    private String pluginKey;

    @Column(name = "bean_name", nullable = false, length = 200)
    private String beanName;

    @Column(name = "description", length = 500)
    private String description;
}
