package com.mipay.sme.domain.ui;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One UI widget a portal build carries (docs/plans/portal-builder.md §7). Each portal build
 * registers its widget manifests at deploy; the portal builder and the RBAC screen discover the
 * widgets a portal can show from the ACTIVE rows. Widgets missing from a newer build are RETIRED,
 * never deleted, so the history of what was deployed where stays.
 */
@Entity
@Table(name = "ui_widget_registrations", indexes = @Index(name = "ix_ui_widget_portal_status", columnList = "portal,status"))
@Getter @Setter
public class UiWidgetRegistration {

    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    @Id
    @Column(length = 36)
    private String id;

    /** The portal build, e.g. supplier-portal@1412038. */
    @Column(nullable = false, length = 200)
    private String bundle;

    /** BusinessPortal: PLATFORM, SUPPLIER, … */
    @Column(nullable = false, length = 32)
    private String portal;

    @Column(name = "widget_id", nullable = false, length = 120)
    private String widgetId;

    @Column(nullable = false, length = 32)
    private String version;

    /** The manifest as registered (JSON): label, cells, actions, sources, size, routes, … */
    @Column(nullable = false, columnDefinition = "text")
    private String manifest;

    @Column(nullable = false, length = 16)
    private String status = ACTIVE;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "retired_at")
    private Instant retiredAt;
}
