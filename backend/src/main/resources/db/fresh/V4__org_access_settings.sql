-- Org access by feature (docs/plans/admin-console.md): which org-portal menus ("features") an org
-- has been given, and the preset they came from. The org's admins hold them through one
-- org-defined RBAC role, organization_access@<org>, kept equal to the switched-on features.
CREATE TABLE public.org_access_settings (
    org_id character varying(36) NOT NULL,
    preset_code character varying(40),
    features text NOT NULL,
    updated_by character varying(36),
    updated_at timestamp(6) with time zone NOT NULL,
    applied_at timestamp(6) with time zone,
    apply_error text,
    CONSTRAINT org_access_settings_pkey PRIMARY KEY (org_id),
    CONSTRAINT fk_org_access_settings_org FOREIGN KEY (org_id) REFERENCES public.organizations(id)
);
