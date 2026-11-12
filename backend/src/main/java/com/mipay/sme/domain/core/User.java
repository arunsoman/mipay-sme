package com.mipay.sme.domain.core;

import com.mipay.sme.domain.Enums.UserRole;
import com.mipay.sme.domain.Enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter @Setter
public class User {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    private String name;

    /** Not unique (V20): one person may administer several organizations. */
    @Column(nullable = false)
    private String phone;

    private String email;

    /** BCrypt hash, or a delegated {noop} value for the seeded demo users. */
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    /** Org node this user belongs to (org hierarchy, V4). */
    @Column(name = "org_id", length = 36)
    private String orgId;

    /** ADMIN / STAFF inside the org; drives money-moving endpoint access. */
    @Enumerated(EnumType.STRING)
    @Column(name = "org_role")
    private com.mipay.sme.domain.Enums.OrgMemberRole orgRole;

    /** Optional username login identifier (3-32, [a-z][a-z0-9._-], H6). */
    @Column(unique = true, length = 32)
    private String username;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    /** Set when a tree-onboarded/admin-activated user activates their account. */
    @Column(name = "activated_at")
    private java.time.Instant activatedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
        if (username == null && phone != null) {
            username = phone;   // fixtures that save directly still get a usable username ([H3])
        }
    }
}
