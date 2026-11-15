-- Portal layouts (docs/plans/portal-builder.md steps 5 + 7, 2026-11-16): menus, screens and placed
-- widgets per portal, built at portalbuilder.afriqpay.com. Maker/checker: a DRAFT is submitted
-- (PENDING) by one builder user and published by another; portals draw the PUBLISHED one.
CREATE TABLE public.ui_layouts (
    id character varying(36) NOT NULL,
    portal character varying(32) NOT NULL,
    status character varying(16) NOT NULL,
    version integer,
    layout text NOT NULL,
    saved_by character varying(80) NOT NULL,
    saved_at timestamp(6) with time zone NOT NULL,
    submitted_by character varying(80),
    submitted_at timestamp(6) with time zone,
    decided_by character varying(80),
    decided_at timestamp(6) with time zone,
    note text,
    CONSTRAINT ui_layouts_pkey PRIMARY KEY (id)
);

CREATE INDEX ix_ui_layouts_portal_status ON public.ui_layouts (portal, status);
-- one draft, one pending and one published layout per portal
CREATE UNIQUE INDEX ux_ui_layouts_live ON public.ui_layouts (portal, status) WHERE status IN ('DRAFT', 'PENDING', 'PUBLISHED');
