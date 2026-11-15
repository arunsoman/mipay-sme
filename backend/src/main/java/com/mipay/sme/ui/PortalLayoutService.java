package com.mipay.sme.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.ui.UiLayout;
import com.mipay.sme.domain.ui.UiLayout.Status;
import com.mipay.sme.repository.UiLayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Portal layouts built at portalbuilder.afriqpay.com (docs/plans/portal-builder.md steps 5 + 7).
 *
 * <p>Per portal there is at most one DRAFT (the builder saves over it), one PENDING (submitted
 * for approval) and one PUBLISHED (what the portal draws). Maker/checker: whoever submitted a
 * layout can't approve it. Publishing retires the previous layout (kept for history and rollback).
 *
 * <p>The builder validates fully (labels, translations, widget spans); the server re-checks the
 * shape every portal relies on, so a broken layout can never reach a sidebar.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortalLayoutService {

    public static final Set<String> PORTALS = Set.of("PLATFORM", "MANUFACTURER", "DISTRIBUTOR", "SUPPLIER", "MERCHANT", "LENDER");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");
    private static final Pattern WIDGET = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");
    private static final int MAX_BYTES = 512 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UiLayoutRepository repo;

    // ------------------------------------------------------------------ reads

    public record Summary(String id, String status, Integer version, String savedBy, Instant savedAt, String submittedBy,
                          Instant submittedAt, String decidedBy, Instant decidedAt, String note) {
        static Summary of(UiLayout l) {
            return new Summary(l.getId(), l.getStatus().name(), l.getVersion(), l.getSavedBy(), l.getSavedAt(),
                    l.getSubmittedBy(), l.getSubmittedAt(), l.getDecidedBy(), l.getDecidedAt(), l.getNote());
        }
    }

    /** The builder's view of one portal: the three live layouts and recent history. */
    @Transactional(readOnly = true)
    public Map<String, Object> state(String portal) {
        String p = portal(portal);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("portal", p);
        for (Status s : List.of(Status.DRAFT, Status.PENDING, Status.PUBLISHED)) {
            UiLayout l = repo.findFirstByPortalAndStatus(p, s).orElse(null);
            out.put(s.name().toLowerCase(), l == null ? null : Map.of("summary", Summary.of(l), "layout", parse(l.getLayout())));
        }
        out.put("history", repo.findTop20ByPortalAndStatusInOrderByDecidedAtDesc(p, List.of(Status.RETIRED, Status.REJECTED))
                .stream().map(Summary::of).toList());
        return out;
    }

    /** What a portal draws: the published layout, or null (the portal keeps its coded menus). */
    @Transactional(readOnly = true)
    public JsonNode published(String portal) {
        return repo.findFirstByPortalAndStatus(portal(portal), Status.PUBLISHED).map(l -> {
            JsonNode n = parse(l.getLayout());
            if (n instanceof com.fasterxml.jackson.databind.node.ObjectNode o) o.put("version", l.getVersion());
            return n;
        }).orElse(null);
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public Summary saveDraft(String portal, String user, JsonNode layout) {
        String p = portal(portal);
        validate(p, layout);
        UiLayout d = repo.findFirstByPortalAndStatus(p, Status.DRAFT).orElseGet(() -> fresh(p, Status.DRAFT));
        d.setLayout(layout.toString());
        d.setSavedBy(user);
        d.setSavedAt(Instant.now());
        return Summary.of(repo.save(d));
    }

    /** The draft goes for approval (it replaces an earlier pending one). */
    @Transactional
    public Summary submit(String portal, String user, String note) {
        String p = portal(portal);
        UiLayout d = repo.findFirstByPortalAndStatus(p, Status.DRAFT)
                .orElseThrow(() -> new NotFoundException("No draft to submit: save one first"));
        validate(p, parse(d.getLayout()));
        repo.findFirstByPortalAndStatus(p, Status.PENDING).ifPresent(old -> {
            old.setStatus(Status.REJECTED);
            old.setDecidedBy(user);
            old.setDecidedAt(Instant.now());
            old.setNote("replaced by a newer submission");
            repo.saveAndFlush(old);
        });
        d.setStatus(Status.PENDING);
        d.setSubmittedBy(user);
        d.setSubmittedAt(Instant.now());
        d.setNote(note);
        return Summary.of(repo.save(d));
    }

    /** Approve (publish) or reject the pending layout. Never by the person who submitted it. */
    @Transactional
    public Summary decide(String portal, String user, boolean approve, String note) {
        String p = portal(portal);
        UiLayout pending = repo.findFirstByPortalAndStatus(p, Status.PENDING)
                .orElseThrow(() -> new NotFoundException("Nothing is waiting for approval"));
        if (user.equalsIgnoreCase(pending.getSubmittedBy())) {
            throw new ConflictException("FOUR_EYES", "You submitted this layout; another builder user must approve or reject it");
        }
        Instant now = Instant.now();
        if (!approve) {
            pending.setStatus(Status.REJECTED);
        } else {
            int next = 1;
            UiLayout live = repo.findFirstByPortalAndStatus(p, Status.PUBLISHED).orElse(null);
            if (live != null) {
                next = (live.getVersion() == null ? 0 : live.getVersion()) + 1;
                live.setStatus(Status.RETIRED);
                repo.saveAndFlush(live);
            }
            pending.setStatus(Status.PUBLISHED);
            pending.setVersion(next);
            log.info("[layout] {} v{} published by {} (submitted by {})", p, next, user, pending.getSubmittedBy());
        }
        pending.setDecidedBy(user);
        pending.setDecidedAt(now);
        if (note != null && !note.isBlank()) pending.setNote(note);
        return Summary.of(repo.save(pending));
    }

    /** Emergency rollback: the published layout is retired and the portal is back on its coded menus (any builder user). */
    @Transactional
    public void unpublish(String portal, String user) {
        String p = portal(portal);
        UiLayout live = repo.findFirstByPortalAndStatus(p, Status.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Nothing is published"));
        live.setStatus(Status.RETIRED);
        live.setDecidedBy(user);
        live.setDecidedAt(Instant.now());
        live.setNote("unpublished: the portal is back on its coded menus");
        repo.save(live);
        log.warn("[layout] {} unpublished by {}", p, user);
    }

    // ------------------------------------------------------------------ validation (the shape portals rely on)

    static void validate(String portal, JsonNode l) {
        if (l == null || !l.isObject()) throw bad("the layout must be a JSON object");
        if (l.toString().length() > MAX_BYTES) throw bad("the layout is larger than 512 KB");
        if (l.path("format").asInt() != 1) throw bad("format must be 1");
        if (!portal.equals(l.path("portal").asText())) throw bad("the layout is for " + l.path("portal").asText() + ", not " + portal);
        JsonNode menus = l.path("menus");
        if (!menus.isArray()) throw bad("menus must be a list");
        Set<String> menuIds = new java.util.HashSet<>();
        Set<String> uids = new java.util.HashSet<>();
        for (JsonNode m : menus) {
            String mid = m.path("id").asText();
            if (!ID.matcher(mid).matches()) throw bad("menu id \"" + mid + "\" must be lower case letters, digits and dashes");
            if (!menuIds.add(mid)) throw bad("menu id \"" + mid + "\" is used twice");
            label(m, "menu " + mid);
            if (!m.path("screens").isArray()) throw bad("menu " + mid + ": screens must be a list");
            Set<String> screenIds = new java.util.HashSet<>();
            for (JsonNode s : m.path("screens")) {
                String sid = s.path("id").asText();
                String where = mid + " › " + sid;
                if (!ID.matcher(sid).matches()) throw bad(where + ": id must be lower case letters, digits and dashes");
                if (!screenIds.add(sid)) throw bad(where + ": submenu id used twice");
                String uid = s.path("uid").asText();
                if (uid.isBlank() || !uids.add(uid)) throw bad(where + ": uid missing or used twice");
                label(s, where);
                if (!s.path("items").isArray()) throw bad(where + ": items must be a list");
                for (JsonNode it : s.path("items")) {
                    String w = it.path("widgetId").asText();
                    if (!WIDGET.matcher(w).matches()) throw bad(where + ": \"" + w + "\" is not a widget id");
                    if (it.path("instanceId").asText().isBlank()) throw bad(where + ": a placed widget has no instanceId");
                }
            }
        }
    }

    private static void label(JsonNode n, String where) {
        String en = n.path("label").path("messages").path("en").asText("");
        if (en.isBlank()) throw bad(where + ": the English label is missing");
        if (en.contains("<") || en.contains(">")) throw bad(where + ": no markup in labels");
    }

    private static BadRequestException bad(String m) {
        return new BadRequestException("LAYOUT_INVALID", m);
    }

    private static String portal(String portal) {
        String p = portal == null ? "" : portal.toUpperCase(java.util.Locale.ROOT);
        if (!PORTALS.contains(p)) throw new BadRequestException("PORTAL_UNKNOWN", "unknown portal " + portal + "; one of " + PORTALS);
        return p;
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("stored layout is not JSON", e);
        }
    }

    private static UiLayout fresh(String portal, Status status) {
        UiLayout l = new UiLayout();
        l.setId(UUID.randomUUID().toString());
        l.setPortal(portal);
        l.setStatus(status);
        return l;
    }
}
