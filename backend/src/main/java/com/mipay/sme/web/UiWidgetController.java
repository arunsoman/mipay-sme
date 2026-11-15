package com.mipay.sme.web;

import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.ui.WidgetRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Widget discovery for the portal builder and the RBAC screen: the widgets a portal's deployed
 * build carries, with their permissions, actions, sizes and routes. A read-only platform
 * catalogue, like the permission inventory, so the same cell opens it.
 */
@RestController
@RequiredArgsConstructor
public class UiWidgetController {

    private final WidgetRegistryService registry;

    @Perm(resource = "admin.access", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View the UI widget registry")
    @GetMapping("/api/ui/widgets")
    public List<Map<String, Object>> widgets(@RequestParam String portal) {
        return registry.active(portal.toUpperCase());
    }

    /**
     * The same catalogue for an org's role builder (the rbac.new-role widget): an org admin who
     * may read the org's roles needs to see which widgets - and which operations on them - a role
     * can be given, without the platform admin.access cell.
     */
    @Perm(resource = "rbac.role", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "View the UI widgets a portal carries, to build an org's roles (PATH tenant)")
    @GetMapping("/api/orgs/{id}/ui/widgets")
    public List<Map<String, Object>> orgWidgets(@PathVariable String id, @RequestParam String portal) {
        return registry.active(portal.toUpperCase());
    }
}
