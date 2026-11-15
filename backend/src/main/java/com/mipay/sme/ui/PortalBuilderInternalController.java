package com.mipay.sme.ui;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The portal builder's API (portalbuilder.afriqpay.com; docs/plans/portal-builder.md steps 5 + 7).
 *
 * <p>Like /internal/ui/widgets/register it is not under /api and never routed by the gateway.
 * The builder site's nginx reaches it directly: nginx checks the builder user's password (basic
 * auth), then forwards the user name ({@code X-Builder-User}) with the shared builder key
 * ({@code X-Builder-Key} = {@code app.ui-builder.key}). Without a configured key the API is off (404).
 * The user name is what maker/checker compares: whoever submits can't approve.
 */
@RestController
@RequiredArgsConstructor
public class PortalBuilderInternalController {

    private static final Pattern USER = Pattern.compile("^[A-Za-z0-9._@-]{1,80}$");
    private final PortalLayoutService layouts;

    @Value("${app.ui-builder.key:}")
    private String key;

    private String user(String presentedKey, String user) {
        if (key == null || key.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "builder API disabled");
        if (presentedKey == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                presentedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid builder key");
        }
        if (user == null || !USER.matcher(user).matches()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no builder user");
        return user;
    }

    @GetMapping("/internal/ui/builder/whoami")
    public Map<String, Object> whoami(@RequestHeader(value = "X-Builder-Key", required = false) String k,
                                      @RequestHeader(value = "X-Builder-User", required = false) String u) {
        return Map.of("user", user(k, u), "portals", PortalLayoutService.PORTALS);
    }

    @GetMapping("/internal/ui/builder/layouts/{portal}")
    public Map<String, Object> state(@PathVariable String portal,
                                     @RequestHeader(value = "X-Builder-Key", required = false) String k,
                                     @RequestHeader(value = "X-Builder-User", required = false) String u) {
        user(k, u);
        return layouts.state(portal);
    }

    @PutMapping("/internal/ui/builder/layouts/{portal}/draft")
    public PortalLayoutService.Summary save(@PathVariable String portal, @RequestBody JsonNode layout,
                                            @RequestHeader(value = "X-Builder-Key", required = false) String k,
                                            @RequestHeader(value = "X-Builder-User", required = false) String u) {
        return layouts.saveDraft(portal, user(k, u), layout);
    }

    public record NoteBody(String note) { }

    @PostMapping("/internal/ui/builder/layouts/{portal}/submit")
    public PortalLayoutService.Summary submit(@PathVariable String portal, @RequestBody(required = false) NoteBody body,
                                              @RequestHeader(value = "X-Builder-Key", required = false) String k,
                                              @RequestHeader(value = "X-Builder-User", required = false) String u) {
        return layouts.submit(portal, user(k, u), body == null ? null : body.note());
    }

    public record DecideBody(Boolean approve, String note) { }

    @PostMapping("/internal/ui/builder/layouts/{portal}/decide")
    public PortalLayoutService.Summary decide(@PathVariable String portal, @RequestBody DecideBody body,
                                              @RequestHeader(value = "X-Builder-Key", required = false) String k,
                                              @RequestHeader(value = "X-Builder-User", required = false) String u) {
        String who = user(k, u);
        if (body == null || body.approve() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "approve is required");
        return layouts.decide(portal, who, body.approve(), body.note());
    }

    @PostMapping("/internal/ui/builder/layouts/{portal}/unpublish")
    public Map<String, Object> unpublish(@PathVariable String portal,
                                         @RequestHeader(value = "X-Builder-Key", required = false) String k,
                                         @RequestHeader(value = "X-Builder-User", required = false) String u) {
        layouts.unpublish(portal, user(k, u));
        return Map.of("unpublished", portal);
    }
}
