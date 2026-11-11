package com.mipay.sme.service.kyc;

import java.util.List;
import java.util.Map;

/**
 * A cross-field validator from the fixed registry (§3.3). There are no
 * executable expressions in the database; validators receive the document,
 * rule parameters and the workflow facts, and return problem paths.
 */
public interface KycRuleValidator {

    String key();

    /**
     * @param params rule params (mutable JSON)
     * @param ctx    validation context: document, facts, schema helpers, profile params
     * @param out    collects problem paths (field path, e.g. "ubos[0].ownershipPercentage")
     */
    void validate(Map<String, Object> params, KycValidationContext ctx);
}
