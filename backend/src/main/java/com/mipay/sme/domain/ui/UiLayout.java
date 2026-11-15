package com.mipay.sme.domain.ui;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One portal layout in its life: DRAFT → PENDING → PUBLISHED → RETIRED (or REJECTED). */
@Getter
@Setter
@Entity
@Table(name = "ui_layouts")
public class UiLayout {
    public enum Status { DRAFT, PENDING, PUBLISHED, RETIRED, REJECTED }

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "portal", nullable = false, length = 32)
    private String portal;

    @Column(name = "status", nullable = false, length = 16)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    /** Set when published: 1, 2, 3 … per portal. */
    @Column(name = "version")
    private Integer version;

    /** The builder's PortalLayout (menus, screens, placed widgets, label messages), as JSON. */
    @Column(name = "layout", nullable = false, columnDefinition = "text")
    private String layout;

    @Column(name = "saved_by", nullable = false, length = 80)
    private String savedBy;
    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;
    @Column(name = "submitted_by", length = 80)
    private String submittedBy;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "decided_by", length = 80)
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "note", columnDefinition = "text")
    private String note;
}
