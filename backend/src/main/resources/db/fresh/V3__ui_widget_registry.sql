-- UI widget registry (docs/plans/portal-builder.md §7): the widgets each portal build carries,
-- registered at deploy, discovered by the portal builder and the RBAC screen.
CREATE TABLE public.ui_widget_registrations (
    id character varying(36) NOT NULL,
    bundle character varying(200) NOT NULL,
    portal character varying(32) NOT NULL,
    widget_id character varying(120) NOT NULL,
    version character varying(32) NOT NULL,
    manifest text NOT NULL,
    status character varying(16) NOT NULL,
    registered_at timestamp(6) with time zone NOT NULL,
    retired_at timestamp(6) with time zone,
    CONSTRAINT ui_widget_registrations_pkey PRIMARY KEY (id)
);

CREATE INDEX ix_ui_widget_portal_status ON public.ui_widget_registrations (portal, status);

-- at most one ACTIVE version of a widget per portal
CREATE UNIQUE INDEX ux_ui_widget_active ON public.ui_widget_registrations (portal, widget_id) WHERE status = 'ACTIVE';
