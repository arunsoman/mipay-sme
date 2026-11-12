-- Extended RBAC (docs/plans/extended-rbac.md ER1): the roles each org may use and hand down.
-- No row = no explicit ceiling: the org inherits the nearest ancestor's hand-down set (X2).
-- Role names are comma-separated; the core pushes them to RBAC with the org-tree sync (X6).
CREATE TABLE org_role_ceilings (
    org_id varchar(36) PRIMARY KEY REFERENCES organizations(id),
    roles text NOT NULL,
    delegable_roles text NOT NULL DEFAULT '',
    set_by varchar(36),
    set_at timestamptz NOT NULL DEFAULT now()
);
