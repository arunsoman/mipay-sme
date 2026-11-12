package com.mipay.sme.domain.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * An org's explicit role ceiling (docs/plans/extended-rbac.md X1): the RBAC roles its users
 * may hold in it, and the subset it may hand down to orgs it onboards. Absent = inherit.
 */
@Getter
@Setter
@Entity
@Table(name = "org_role_ceilings")
public class OrgRoleCeiling {

    @Id
    @Column(name = "org_id", length = 36)
    private String orgId;

    /** Comma-separated role names. */
    @Column(name = "roles", nullable = false, columnDefinition = "text")
    private String roles = "";

    @Column(name = "delegable_roles", nullable = false, columnDefinition = "text")
    private String delegableRoles = "";

    @Column(name = "set_by", length = 36)
    private String setBy;

    @Column(name = "set_at", nullable = false)
    private Instant setAt;

    @PrePersist
    @PreUpdate
    void touch() {
        setAt = Instant.now();
    }

    public Set<String> roleSet() {
        return split(roles);
    }

    public Set<String> delegableSet() {
        return split(delegableRoles);
    }

    public void setRoleSet(Collection<String> r) {
        roles = String.join(",", new TreeSet<>(r));
    }

    public void setDelegableSet(Collection<String> r) {
        delegableRoles = String.join(",", new TreeSet<>(r));
    }

    static Set<String> split(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null || s.isBlank()) return out;
        Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).forEach(out::add);
        return out;
    }
}
