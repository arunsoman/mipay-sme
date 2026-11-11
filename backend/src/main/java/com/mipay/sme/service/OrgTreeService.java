package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgRightKind;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrgRightRepository;
import com.mipay.sme.repository.OrgSubtreeRow;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.SuspensionRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Org-tree reading ([D4]): nested tree from ONE closure query, paged
 * descendants, root-first path, 404-visibility ([M10]), effective status from
 * lifecycle + cascading suspension holds, contact masking by distance, and
 * suspension-impact previews.
 */
@Service
@RequiredArgsConstructor
public class OrgTreeService {

    public static final int MAX_TREE_NODES = 5000;

    private final OrganizationRepository orgRepo;
    private final OrgClosureRepository closureRepo;
    private final OrgRightRepository rightRepo;
    private final SuspensionRepository suspensionRepo;
    private final UserRepository userRepo;
    private final OrgCallerService callerService;
    private final OrgRightsService rightsService;
    private final OrgAccess orgAccess;

    // ------------------------------------------------------------------
    // callers / visibility
    // ------------------------------------------------------------------

    public boolean platformStaff(Current.Principal caller) {
        return callerService.platformStaff(caller);
    }

    public Organization orgOf(Current.Principal caller) {
        return callerService.orgOf(caller);
    }

    /** Caller's own org node; platform staff resolve to the PLATFORM root org. */
    public Organization ownOrg(Current.Principal caller) {
        Organization org = orgOf(caller);
        if (platformStaff(caller)) {
            if (org == null) {
                return orgRepo.findById(OrgProvisioningService.ORG_ROOT).orElse(null);
            }
            if (org.getOrgType() == Enums.OrgType.PLATFORM) return org;
        }
        if (org == null) throw new NotFoundException("Caller has no organization");
        return org;
    }

    /** Public 404-visibility assert (never 403 - existence leaks nothing) [M10]. */
    public void visible(Current.Principal caller, String orgId) {
        Organization target = orgRepo.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        assertVisible(caller, target);
    }

    private void assertVisible(Current.Principal caller, Organization target) {
        if (platformStaff(caller)) return;
        Organization callerOrg = orgOf(caller);
        if (callerOrg == null) throw new NotFoundException("Organization not found");
        if (callerOrg.getId().equals(target.getId())) return;
        if (callerOrg.getOrgType() == Enums.OrgType.PLATFORM) return;
        if (!closureRepo.existsByAncestorIdAndDescendantId(callerOrg.getId(), target.getId())) {
            throw new NotFoundException("Organization not found");
        }
    }

    // ------------------------------------------------------------------
    // effective status (lifecycle + active suspension holds, own and inherited)
    // ------------------------------------------------------------------

    /** Org ids among the set with at least one active hold of their own. */
    public Set<String> directlySuspended(List<String> orgIds) {
        return orgAccess.directlySuspended(orgIds);
    }

    public String effectiveStatus(Organization o, Set<String> directlyHeldOrgIds) {
        switch (o.getLifecycle()) {
            case PENDING -> { return "PENDING"; }
            case REJECTED -> { return "REJECTED"; }
            case BLOCKED -> { return "BLOCKED"; }
            default -> { }
        }
        if (directlyHeldOrgIds.contains(o.getId())) return "SUSPENDED";
        String parentId = o.getParentId();
        Set<String> seen = new HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            if (directlyHeldOrgIds.contains(parentId)) return "SUSPENDED_BY_ANCESTOR";
            parentId = orgRepo.findById(parentId).map(Organization::getParentId).orElse(null);
        }
        return "ACTIVE";
    }

    public boolean operational(Organization o, Set<String> directlyHeldOrgIds) {
        return "ACTIVE".equals(effectiveStatus(o, directlyHeldOrgIds));
    }

    // ------------------------------------------------------------------
    // tree() — one closure query, nested in memory
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Dtos.OrgNodeRow tree(Current.Principal caller, String orgId, Integer maxDepth, String status) {
        Organization anchor = orgRepo.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        assertVisible(caller, anchor);

        List<OrgSubtreeRow> rows = closureRepo.findSubtree(orgId);
        if (rows.size() > MAX_TREE_NODES) {
            throw new ConflictException("TREE_TOO_LARGE",
                    "Subtree exceeds " + MAX_TREE_NODES + " nodes; use the descendants endpoint");
        }
        // holds anywhere in the subtree + on the anchor's ancestors ([H4] cascades by query)
        List<String> ids = rows.stream().map(r -> r.getOrg().getId()).toList();
        Set<String> held = orgAccess.directlySuspended(ids);
        held.addAll(orgAccess.directlySuspended(ancestorIds(anchor)));

        Map<String, List<com.mipay.sme.domain.ops.Suspension>> holdsByOrg = suspensionRepo.findByOrgIdIn(ids).stream()
                .collect(Collectors.groupingBy(com.mipay.sme.domain.ops.Suspension::getOrgId));

        Map<String, List<com.mipay.sme.domain.org.OrgRight>> rightsByOrg = rightRepo.findByOrgIdIn(ids).stream()
                .collect(Collectors.groupingBy(com.mipay.sme.domain.org.OrgRight::getOrgId));

        Map<String, List<User>> usersByOrg = userRepo.findByOrgIdIn(ids).stream()
                .collect(Collectors.groupingBy(User::getOrgId));

        // caller-relative depths drive contact masking [M10]
        Map<String, Integer> callerDepths = callerDepths(caller);
        int anchorCallerDepth = callerDepths.getOrDefault(orgId, -1);

        Map<String, List<Dtos.OrgUserRow>> nodeUsers = new LinkedHashMap<>();
        Map<String, Long> userCounts = new LinkedHashMap<>();
        for (OrgSubtreeRow r : rows) {
            Organization o = r.getOrg();
            int rel = callerDepths.getOrDefault(o.getId(), -1) - anchorCallerDepth;
            boolean near = platformStaff(caller) || (rel >= 0 && rel <= 1) || platformRoot(caller);
            List<Dtos.OrgUserRow> ur = usersByOrg.getOrDefault(o.getId(), List.of()).stream()
                    .map(u -> new Dtos.OrgUserRow(u.getId(), u.getName(), u.getUsername(), u.getRole().name(),
                            orgRole(u), statusOf(u), near ? u.getPhone() : maskPhone(u.getPhone()),
                            near ? u.getEmail() : maskEmail(u.getEmail())))
                    .toList();
            nodeUsers.put(o.getId(), ur);
            userCounts.put(o.getId(), (long) ur.size());
        }

        // mutable builders, leaves first so subtree counts are computable bottom-up
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (OrgSubtreeRow r : rows.stream()
                .sorted(Comparator.comparingInt(OrgSubtreeRow::getDepth).reversed())
                .toList()) {
            Node n = new Node();
            n.id = r.getOrg().getId();
            nodes.put(n.id, n);
        }
        for (OrgSubtreeRow r : rows) {
            Organization o = r.getOrg();
            if (r.getDepth() > 0 && nodes.containsKey(o.getParentId())) {
                nodes.get(o.getParentId()).children.add(nodes.get(o.getId()));
            }
        }
        // counts bottom-up: suspension impact preview = subtree orgs/users [D4]
        for (OrgSubtreeRow r : rows.stream()
                .sorted(Comparator.comparingInt(OrgSubtreeRow::getDepth).reversed())
                .toList()) {
            Node n = nodes.get(r.getOrg().getId());
            long desc = 0, descActive = 0, usersTotal = 0, usersActive = 0;
            for (Node c : n.children) {
                desc += 1 + c.descendants;
                descActive += c.descendantsActive;
                usersTotal += c.usersTotal;
                usersActive += c.usersActive;
            }
            Organization o = r.getOrg();
            if ("ACTIVE".equals(effectiveStatus(o, held))) descActive += 1;
            long own = userCounts.getOrDefault(o.getId(), 0L);
            usersTotal += own;
            if (!held.contains(o.getId()) && o.getLifecycle() == Enums.OrgStatus.ACTIVE) usersActive += own;
            n.descendants = desc;
            n.descendantsActive = descActive;
            n.usersTotal = usersTotal;
            n.usersActive = usersActive;
        }

        // node rows; filters applied before lookup of the anchor node
        Map<String, Dtos.OrgNodeRow> built = new LinkedHashMap<>();
        for (OrgSubtreeRow r : rows) {
            Organization o = r.getOrg();
            if (maxDepth != null && r.getDepth() > maxDepth) continue;
            String eff = effectiveStatus(o, held);
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(eff)) continue;
            List<Dtos.OrgHoldRow> holds = holdsByOrg.getOrDefault(o.getId(), List.of()).stream()
                    .map(h -> new Dtos.OrgHoldRow(h.getId(), h.getReason(), h.getSuspendedAt(),
                            h.getSuspendedBy(), h.getLiftedAt()))
                    .toList();
            Node n = nodes.get(o.getId());
            Dtos.OrgSubtreeCounts counts = new Dtos.OrgSubtreeCounts(
                    n.descendants, n.descendantsActive, n.usersTotal, n.usersActive);
            // effective rights for display ([H7]); computed only for orgs that carry rights rows
            boolean hasRights = rightsByOrg.containsKey(o.getId());
            List<String> effDirectList = hasRights
                    ? orgTypeNames(rightsService.effDirect(o.getId())) : List.of();
            List<String> effCeilingList = hasRights
                    ? rightsService.effCeilingDisplay(o.getId()) : List.of();
            Dtos.OrgRightsView rightsView = hasRights
                    ? new Dtos.OrgRightsView(String.join(",", effDirectList), String.join(",", effCeilingList),
                    String.join(",", effDirectList), String.join(",", effCeilingList)) : null;
            built.put(o.getId(), new Dtos.OrgNodeRow(
                    o.getId(), o.getOrgType().name(), o.getName(), o.getLifecycle().name(), eff,
                    holds.size(), holds,
                    o.getOnboardedBy(), o.getOnboardedAt(),
                    effDirectList,
                    nodeUsers.getOrDefault(o.getId(), List.of()),
                    userCounts.getOrDefault(o.getId(), 0L), counts,
                    rightsView, List.of()));
        }
        // attach the visible child hierarchy (leaves first); filtered-out nodes
        // drop out of the parent's children list as well
        Map<String, Dtos.OrgNodeRow> withKids = new LinkedHashMap<>();
        for (OrgSubtreeRow r : rows.stream()
                .sorted(Comparator.comparingInt(OrgSubtreeRow::getDepth).reversed())
                .toList()) {
            Dtos.OrgNodeRow base = built.get(r.getOrg().getId());
            if (base == null) continue;
            List<Dtos.OrgNodeRow> kids = new ArrayList<>();
            for (Node child : nodes.get(r.getOrg().getId()).children) {
                Dtos.OrgNodeRow ck = withKids.get(child.id);
                if (ck != null) kids.add(ck);
            }
            withKids.put(base.id(), new Dtos.OrgNodeRow(base.id(), base.type(), base.name(),
                    base.lifecycle(), base.effectiveStatus(), base.activeHolds(), base.holds(),
                    base.onboardedBy(), base.onboardedAt(), base.effectiveRights(),
                    base.users(), base.userCount(), base.subtree(), base.rights(), kids));
        }
        Dtos.OrgNodeRow top = withKids.get(orgId) == null ? built.get(orgId) : withKids.get(orgId);
        if (top == null) throw new NotFoundException("Organization not found (no node passed the filter)");
        return top;
    }

    private List<String> ancestorIds(Organization anchor) {
        List<String> ids = new ArrayList<>();
        String parentId = anchor.getParentId();
        Set<String> seen = new HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            ids.add(parentId);
            parentId = orgRepo.findById(parentId).map(Organization::getParentId).orElse(null);
        }
        return ids;
    }

    private Map<String, Integer> callerDepths(Current.Principal caller) {
        Organization callerOrg = orgOf(caller);
        if (callerOrg == null) return Map.of();
        return closureRepo.findSubtree(callerOrg.getId()).stream()
                .collect(Collectors.toMap(r -> r.getOrg().getId(), OrgSubtreeRow::getDepth, (a, b) -> a));
    }

    private boolean platformRoot(Current.Principal caller) {
        Organization callerOrg = orgOf(caller);
        return callerOrg != null && callerOrg.getOrgType() == Enums.OrgType.PLATFORM;
    }

    // ------------------------------------------------------------------
    // descendants() — flat, paged
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageImpl<Dtos.OrgDescendantRow> descendants(Current.Principal caller, String orgId,
                                                       String type, String status, int page, int size) {
        Organization anchor = orgRepo.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        assertVisible(caller, anchor);

        List<OrgSubtreeRow> rows = closureRepo.findSubtree(orgId).stream()
                .filter(r -> r.getDepth() > 0)
                .filter(r -> type == null || type.isBlank()
                        || r.getOrg().getOrgType().name().equalsIgnoreCase(type))
                .toList();
        List<String> ids = rows.stream().map(r -> r.getOrg().getId()).toList();
        Set<String> held = orgAccess.directlySuspended(ids);
        held.addAll(orgAccess.directlySuspended(ancestorIds(anchor)));
        List<Dtos.OrgDescendantRow> all = new ArrayList<>();
        for (OrgSubtreeRow r : rows) {
            String eff = effectiveStatus(r.getOrg(), held);
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(eff)) continue;
            all.add(new Dtos.OrgDescendantRow(r.getOrg().getId(), r.getOrg().getOrgType().name(),
                    r.getOrg().getName(), r.getOrg().getLifecycle().name(), eff, r.getDepth()));
        }
        int from = Math.min(page * Math.max(1, size), all.size());
        int to = Math.min(from + Math.max(1, size), all.size());
        return new PageImpl<>(all.subList(from, to), PageRequest.of(page, Math.max(1, size)), all.size());
    }

    // ------------------------------------------------------------------
    // path() — ancestors, root first
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Dtos.OrgPathRow> path(Current.Principal caller, String orgId) {
        Organization target = orgRepo.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        assertVisible(caller, target);
        return closureRepo.findAncestors(orgId).stream()
                .map(r -> new Dtos.OrgPathRow(r.getOrg().getId(), r.getOrg().getOrgType().name(),
                        r.getOrg().getName(), r.getDepth()))
                .toList();
    }

    // ------------------------------------------------------------------
    // masking helpers [M10]
    // ------------------------------------------------------------------

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "•••";
        return phone.substring(0, 2) + "•••" + phone.substring(phone.length() - 2);
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "•••";
        return email.substring(0, 1) + "•••" + email.substring(at);
    }

    private String statusOf(User u) {
        return u.getStatus() == null ? null : u.getStatus().name();
    }

    private String orgRole(User u) {
        return u.getOrgRole() == null ? null : u.getOrgRole().name();
    }

    private static List<String> orgTypeNames(java.util.Set<Enums.OrgType> set) {
        return set.stream().map(Enum::name).sorted().toList();
    }

    /** Mutable in-memory tree node used for bottom-up subtree counting. */
    private static final class Node {
        String id;
        List<Node> children = new ArrayList<>();
        long descendants;
        long descendantsActive;
        long usersTotal;
        long usersActive;
    }
}
