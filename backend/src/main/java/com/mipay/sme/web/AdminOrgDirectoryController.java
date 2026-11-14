package com.mipay.sme.web;

import com.mipay.sme.domain.Enums.OrgStatus;
import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Platform-wide organization directory. The platform permission is checked
 * by the same RBAC decision plane as every other administrative endpoint. */
@RestController
@RequestMapping("/api/admin/organizations")
@RequiredArgsConstructor
public class AdminOrgDirectoryController {
    private final OrganizationRepository organizations;

    public record Row(String id, String name, OrgType type, String parentId, OrgStatus status,
                      String country, String registrationNumber, Instant createdAt) {
        static Row of(Organization o) {
            return new Row(o.getId(), o.getName(), o.getOrgType(), o.getParentId(), o.getLifecycle(),
                    o.getCountry(), o.getRegistrationNumber(), o.getCreatedAt());
        }
    }

    @Perm(resource = "admin.org_directory", verb = Verb.READ, tenantSource = TenantSource.NONE,
          description = "View the platform-wide organization directory")
    @GetMapping
    public Page<Row> list(@RequestParam(required = false) OrgType type,
                          @RequestParam(required = false) OrgStatus status,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "50") int size) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, size));
        return organizations.directory(type, status,
                PageRequest.of(boundedPage, boundedSize, Sort.by("name").ascending())).map(Row::of);
    }
}
