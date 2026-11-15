package com.mipay.sme.domain.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The org-portal menus ("features") an org has been given (docs/plans/admin-console.md).
 * {@code features} is a comma list of menu ids; the org's admins hold them through the
 * org-defined RBAC role {@code organization_access@<org>}. {@code applyError} records why RBAC
 * refused the last change (shown as a warning until it succeeds).
 */
@Getter
@Setter
@Entity
@Table(name = "org_access_settings")
public class OrgAccessSetting {
    @Id
    @Column(name = "org_id", length = 36)
    private String orgId;

    @Column(name = "preset_code", length = 40)
    private String presetCode;

    @Column(name = "features", nullable = false, columnDefinition = "text")
    private String features = "";

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "apply_error", columnDefinition = "text")
    private String applyError;
}
