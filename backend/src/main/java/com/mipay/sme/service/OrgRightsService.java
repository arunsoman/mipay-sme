package com.mipay.sme.service;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgRightKind;
import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.org.OrgClosure;
import com.mipay.sme.domain.org.OrgRight;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrgRightRepository;
import com.mipay.sme.repository.OrgSubtreeRow;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Flexible onboarding rights ([D1/H7/M5]): DIRECT = may onboard itself;
 * CEILING = subtree-wide upper bound. Effective rights derive through the
 * closure table: effCeiling = intersection of ancestor-or-self CEILING rows,
 * effDirect = DIRECT ∩ effCeiling. Narrowing only ([D1]) - no rewriting of
 * descendants, they re-derive.
 */
@Service
@RequiredArgsConstructor
public class OrgRightsService {

    private final OrgRightRepository rightRepo;
    private final OrgClosureRepository closureRepo;
    private final OrganizationRepository orgRepo;
    private final OrgCallerService callerService;
    private final AuditService auditService;

    /** effCeiling(X) = ⋂ CEILING(a) over every ancestor-or-self a of X (self row = depth 0). */
    public Set<OrgType> effCeiling(String orgId) {
        Set<OrgType> ceiling = null;
        for (OrgSubtreeRow r : closureRepo.findAncestors(orgId)) {
            Set<OrgType> row = kinds(r.getOrg().getId()).ceiling();
            ceiling = ceiling == null ? new HashSet<>(row) : intersect(ceiling, row);
        }
        if (ceiling == null) ceiling = new HashSet<>(kinds(orgId).ceiling());
        return ceiling;
    }

    private static Set<OrgType> intersect(Set<OrgType> a, Set<OrgType> b) {
        Set<OrgType> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    /** effDirect(X) = DIRECT(X) ∩ effCeiling(X). */
    public Set<OrgType> effDirect(String orgId) {
        Set<OrgType> direct = new HashSet<>(kinds(orgId).direct());
        direct.retainAll(effCeiling(orgId));
        return direct;
    }

    /** Rows of the org grouped by kind. */
    public record RightsOf(Set<OrgType> direct, Set<OrgType> ceiling) { }

    public RightsOf kinds(String orgId) {
        Set<OrgType> direct = new HashSet<>();
        Set<OrgType> ceiling = new HashSet<>();
        rightRepo.findByOrgIdInAndKindIn(List.of(orgId),
                        List.of(OrgRightKind.DIRECT, OrgRightKind.CEILING)).forEach(r -> {
            if (r.getKind() == OrgRightKind.DIRECT) direct.add(r.getTargetType());
            else ceiling.add(r.getTargetType());
        });
        return new RightsOf(direct, ceiling);
    }

    /**
     * Validate + normalize a proposed (direct, ceiling) for child C onboarded by O.
     * Throws 422 RIGHTS_EXCEED_CEILING / RIGHTS_INVALID_FOR_TYPE with the offending types.
     */
    public Proposal validateProposal(String onboarderOrgId, OrgType childType, Set<OrgType> proposedDirect,
                                     Set<OrgType> proposedCeiling) {
        Set<OrgType> effCeiling = effCeiling(onboarderOrgId);
        Set<OrgType> effDirect = effDirect(onboarderOrgId);
        if (!effDirect.contains(childType)) {
            throw new com.mipay.sme.common.ForbiddenException("NO_RIGHT",
                    "Org may not onboard " + childType);
        }

        Set<OrgType> below = OrgTypeRules.below(childType);
        Set<OrgType> direct = proposedDirect == null || proposedDirect.isEmpty()
                ? new HashSet<>(defaultsDirect(childType)) : new HashSet<>(proposedDirect);
        Set<OrgType> ceiling = proposedCeiling == null || proposedCeiling.isEmpty()
                ? new HashSet<>(below) : new HashSet<>(proposedCeiling);

        // defaults intersected with the onboarder's effective ceiling
        direct.retainAll(effCeiling);
        ceiling.retainAll(effCeiling);

        // direct ⊆ ceiling ⊆ below(childType) ∩ effCeiling(O); no rank at or above the child's own
        Set<OrgType> ceilingViolations = ceiling.stream()
                .filter(t -> !below.contains(t) || !effCeiling.contains(t))
                .collect(java.util.stream.Collectors.toSet());
        if (!ceilingViolations.isEmpty()) {
            throw new SimException("RIGHTS_EXCEED_CEILING",
                    "Ceiling exceeds the onboarder's effective limits: " + sorted(ceilingViolations));
        }
        Set<OrgType> directViolations = direct.stream()
                .filter(t -> !ceiling.contains(t) || !below.contains(t))
                .collect(java.util.stream.Collectors.toSet());
        if (!directViolations.isEmpty()) {
            throw new SimException("RIGHTS_INVALID_FOR_TYPE",
                    "Direct rights invalid for " + childType + ": " + sorted(directViolations));
        }
        return new Proposal(direct, ceiling);
    }

    public record Proposal(Set<OrgType> direct, Set<OrgType> ceiling) { }

    /** Defaults: the standard chain step for DIRECT, below(child) for CEILING. */
    private Set<OrgType> defaultsDirect(OrgType childOnboarderType) {
        OrgType step = OrgTypeRules.standardStep(childOnboarderType);
        return step == null ? Set.of() : Set.of(step);
    }

    /** Rights at decision time must still be within the onboarder's CURRENT effective ceiling. */
    public void assertNotStale(String onboarderOrgId, Set<OrgType> proposedCeiling, Set<OrgType> proposedDirect) {
        Set<OrgType> effCeiling = effCeiling(onboarderOrgId);
        Set<OrgType> bad = new HashSet<>();
        proposedCeiling.stream().filter(t -> !effCeiling.contains(t)).forEach(bad::add);
        if (!bad.isEmpty()) {
            throw new com.mipay.sme.common.ConflictException("RIGHTS_STALE",
                    "Onboarder's effective rights narrowed since submission: " + sorted(bad));
        }
    }

    /** Narrow only ([D1]): new sets must be subsets of the current rights. */
    @Transactional
    public Dtos.OrgRightsView narrow(String orgId, Set<OrgType> newDirect, Set<OrgType> newCeiling,
                                     Current.Principal actor) {
        RightsOf current = kinds(orgId);
        if (newDirect != null) {
            Set<OrgType> invalid = newDirect.stream().filter(t -> !current.direct().contains(t))
                    .collect(java.util.stream.Collectors.toSet());
            if (!invalid.isEmpty()) {
                throw new SimException("RIGHTS_NARROW_ONLY",
                        "Rights may only be narrowed ([D1]); not currently held: " + sorted(invalid));
            }
            replace(orgId, OrgRightKind.DIRECT, newDirect);
        }
        if (newCeiling != null) {
            Set<OrgType> invalid = newCeiling.stream().filter(t -> !current.ceiling().contains(t))
                    .collect(java.util.stream.Collectors.toSet());
            if (!invalid.isEmpty()) {
                throw new SimException("RIGHTS_NARROW_ONLY",
                        "Rights may only be narrowed ([D1]); not currently held: " + sorted(invalid));
            }
            // DIRECT of this org may never exceed its own CEILING
            Set<OrgType> direct = newDirect == null ? current.direct() : newDirect;
            Set<OrgType> dropDirect = direct.stream().filter(t -> !newCeiling.contains(t))
                    .collect(java.util.stream.Collectors.toSet());
            if (!dropDirect.isEmpty()) {
                replace(orgId, OrgRightKind.DIRECT, new HashSet<>(direct.stream()
                        .filter(newCeiling::contains).collect(java.util.stream.Collectors.toSet())));
            }
            replace(orgId, OrgRightKind.CEILING, newCeiling);
        }
        auditService.record(actor.userId(), actor.role(), "org_rights_narrowed", "organization", orgId,
                null, null, "direct=" + sorted(kinds(orgId).direct()) + ";ceiling=" + sorted(kinds(orgId).ceiling()),
                false, null);
        return view(orgId);
    }

    private void replace(String orgId, OrgRightKind kind, Set<OrgType> targets) {
        rightRepo.deleteAll(rightRepo.findByOrgIdInAndKindIn(List.of(orgId), List.of(kind)));
        for (OrgType t : targets) {
            OrgRight r = new OrgRight();
            r.setOrgId(orgId);
            r.setTargetType(t);
            r.setKind(kind);
            r.setGrantedBy("system:narrow");
            r.setGrantedAt(Instant.now());
            rightRepo.save(r);
        }
    }

    public Dtos.OrgRightsView view(String orgId) {
        RightsOf k = kinds(orgId);
        return new Dtos.OrgRightsView(String.join(",", display(k.direct())),
                String.join(",", display(k.ceiling())),
                String.join(",", display(effDirect(orgId))),
                String.join(",", display(effCeiling(orgId))));
    }

    /** Own CEILING rows joined for display (the true effective ceiling derives per node). */
    public List<String> effCeilingDisplay(String orgId) {
        return sorted(kinds(orgId).ceiling());
    }

    /** What child types the caller may propose, with the bounds per type (grantable). */
    public Dtos.GrantableView grantable(Current.Principal caller, String callerOrgId) {
        Set<OrgType> effDirect = effDirect(callerOrgId);
        List<Dtos.GrantableChild> children = effDirect.stream()
                .sorted(java.util.Comparator.comparing((OrgType t) -> -OrgTypeRules.rank(t)))
                .map(t -> new Dtos.GrantableChild(t.name(),
                        display(OrgTypeRules.below(t)),
                        display(OrgTypeRules.standardStep(t) == null
                                ? Set.of() : Set.of(OrgTypeRules.standardStep(t)))))
                .toList();
        return new Dtos.GrantableView(display(effDirect), children);
    }

    private static List<String> display(Set<OrgType> set) {
        return set.stream().map(Enum::name).sorted().toList();
    }

    private static List<String> sorted(Set<OrgType> set) {
        return set.stream().map(Enum::name).sorted().toList();
    }
}
