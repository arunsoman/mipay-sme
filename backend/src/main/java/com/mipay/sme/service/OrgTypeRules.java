package com.mipay.sme.service;

import com.mipay.sme.domain.Enums.OrgType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fixed value-chain bounds — the ONLY hard-coded hierarchy rules
 * (plan §"Fixed value-chain bounds"). Everything else derives from org_rights.
 */
public final class OrgTypeRules {

    private OrgTypeRules() { }

    public static final Map<OrgType, Integer> RANK = Map.of(
            OrgType.PLATFORM, Integer.MAX_VALUE,
            OrgType.MANUFACTURER, 4,
            OrgType.DISTRIBUTOR, 3,
            OrgType.SUPPLIER, 2,
            OrgType.MERCHANT, 1,
            OrgType.LENDER, -1);

    public static final Map<OrgType, List<OrgType>> ALLOWED_PARENTS = Map.of(
            OrgType.MANUFACTURER, List.of(OrgType.PLATFORM),
            OrgType.DISTRIBUTOR, List.of(OrgType.PLATFORM, OrgType.MANUFACTURER),
            OrgType.SUPPLIER, List.of(OrgType.PLATFORM, OrgType.MANUFACTURER, OrgType.DISTRIBUTOR),
            OrgType.MERCHANT, List.of(OrgType.PLATFORM, OrgType.MANUFACTURER,
                    OrgType.DISTRIBUTOR, OrgType.SUPPLIER),
            OrgType.LENDER, List.of(OrgType.PLATFORM),
            OrgType.PLATFORM, List.of());

    /** All types strictly below (rank lower than) the given type; LENDER has rank -, never below. */
    public static final Map<OrgType, Set<OrgType>> BELOW = Map.of(
            OrgType.PLATFORM, Set.of(OrgType.MANUFACTURER, OrgType.DISTRIBUTOR,
                    OrgType.SUPPLIER, OrgType.MERCHANT, OrgType.LENDER),
            OrgType.MANUFACTURER, Set.of(OrgType.DISTRIBUTOR, OrgType.SUPPLIER, OrgType.MERCHANT),
            OrgType.DISTRIBUTOR, Set.of(OrgType.SUPPLIER, OrgType.MERCHANT),
            OrgType.SUPPLIER, Set.of(OrgType.MERCHANT),
            OrgType.MERCHANT, Set.of(),
            OrgType.LENDER, Set.of());

    /** What a type may EVER hold (ceiling upper bound) — LENDER hangs off PLATFORM and holds none. */
    public static final Map<OrgType, Set<OrgType>> MAY_HOLD = Map.copyOf(BELOW);

    /** The standard one-step chain used as the DIRECT default. */
    public static final Map<OrgType, OrgType> STANDARD_STEP = Map.of(
            OrgType.MANUFACTURER, OrgType.DISTRIBUTOR,
            OrgType.DISTRIBUTOR, OrgType.SUPPLIER,
            OrgType.SUPPLIER, OrgType.MERCHANT);

    public static int rank(OrgType t) {
        return RANK.get(t);
    }

    public static boolean parentAllows(OrgType parentType, OrgType childType) {
        return ALLOWED_PARENTS.get(childType).contains(parentType);
    }

    public static Set<OrgType> below(OrgType t) {
        return BELOW.get(t);
    }

    public static OrgType standardStep(OrgType t) {
        return STANDARD_STEP.get(t);
    }
}
