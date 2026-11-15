package com.mipay.sme.web;

import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.ui.WidgetRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
}
