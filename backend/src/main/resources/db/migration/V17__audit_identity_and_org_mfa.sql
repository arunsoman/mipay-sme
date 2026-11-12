-- V16: audit identity upgrade (cutover plan §3.12: replace role-based audit identity with
-- actor kind, link id and authentication assurance where available) and the org MFA policy
-- flag reconciled into IdP MFA profiles (2fa-design §5.1).
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS actor_kind varchar(24);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS link_id varchar(36);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS acr varchar(16);

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS mfa_required boolean NOT NULL DEFAULT false;
