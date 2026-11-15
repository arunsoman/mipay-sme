package com.mipay.sme.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.mipay.sme.security.rbac.PublicEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** What a portal draws: its published layout (204 = none, the portal keeps its coded menus). */
@RestController
@RequiredArgsConstructor
public class PortalLayoutController {
    private final PortalLayoutService layouts;

    // signed-in users only (SecurityConfig: any request is authenticated); no cell, because every
    // user of the portal needs its menu structure - which screens they may open stays with RBAC
    @PublicEndpoint(reason = "menu structure of a portal for its signed-in users; screens stay permission-checked")
    @GetMapping("/api/ui/layouts/{portal}")
    public ResponseEntity<JsonNode> published(@PathVariable String portal) {
        JsonNode l = layouts.published(portal);
        return l == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(l);
    }
}
