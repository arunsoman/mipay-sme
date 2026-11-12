package com.mipay.sme.web;

import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.OrgTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The signed-in user's own permissions in their own org (RBAC + extended RBAC): drives the
 * User management menu (shown to holders of org.user:administer) and the permissions a user
 * manager may put into a new role (never more than they hold themselves).
 */
@RestController
@RequiredArgsConstructor
public class MyAccessController {

    private final OrgTreeService tree;
    private final RbacMeshClient rbac;

    public record HeldPermission(String id, String row, String verb, String rowDescription) { }

    public record MyAccess(String orgId, String orgName, String orgType, List<HeldPermission> permissions) { }

    @SuppressWarnings("unchecked")
    @Perm(resource = "org.user", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "My permissions in my organization (User management)")
    @GetMapping("/api/orgs/me/access")
    public MyAccess myAccess() {
        Current.Principal me = Current.get();
        Organization org = tree.ownOrg(me);
        Map<String, String> described = new HashMap<>();
        try {
            for (Map<String, Object> r : rbac.platformResources()) {
                described.put(String.valueOf(r.get("id")), String.valueOf(r.getOrDefault("description", "")));
            }
        } catch (RbacMeshClient.RbacMeshException ignored) { /* ids only */ }
        List<HeldPermission> out = new ArrayList<>();
        Object ops = rbac.held(me.userId(), org.getId()).get("operations");
        if (ops instanceof List<?> l) {
            for (Object o : l) {
                String id = String.valueOf(o);
                int i = id.indexOf(':');
                if (i < 0) continue;
                String row = id.substring(0, i);
                out.add(new HeldPermission(id, row, id.substring(i + 1), described.getOrDefault(row, row)));
            }
        }
        return new MyAccess(org.getId(), org.getName(), org.getOrgType() == null ? null : org.getOrgType().name(), out);
    }
}
