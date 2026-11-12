-- P3 identity foundation. The org NOT NULL gate follows the explicit clean reset
-- and the lifecycle-writer cutover; this additive migration never deletes users.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_status_check;
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_status;
ALTER TABLE users ADD CONSTRAINT chk_users_status
    CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','ARCHIVED'));

CREATE TABLE identity_links (
    id varchar(36) PRIMARY KEY,
    user_id varchar(36) NOT NULL REFERENCES users(id),
    provider varchar(64) NOT NULL,
    issuer varchar(255) NOT NULL,
    subject varchar(255) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','ACTIVE','DISABLED','REVOKED')),
    idp_enabled boolean NOT NULL DEFAULT false,
    max_aal varchar(16) NOT NULL DEFAULT 'AAL1',
    created_by varchar(36),
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    last_login_at timestamptz,
    CONSTRAINT uq_identity_issuer_subject UNIQUE (issuer, subject)
);
CREATE INDEX ix_identity_links_user ON identity_links(user_id);
CREATE UNIQUE INDEX uq_identity_trustplane_user ON identity_links(user_id)
    WHERE provider = 'trustplane' AND status <> 'REVOKED';

-- Outbox rows contain commands, never setup links or OTP values. A worker leases
-- and retries them after the committing core transaction has completed.
CREATE TABLE auth_outbox (
    id varchar(36) PRIMARY KEY,
    user_id varchar(36) NOT NULL REFERENCES users(id),
    provider varchar(64) NOT NULL,
    command varchar(64) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    state varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING','RUNNING','DONE','FAILED')),
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    last_error varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    dedupe_key varchar(255) UNIQUE
);
CREATE INDEX ix_auth_outbox_ready ON auth_outbox(state, available_at);

CREATE TABLE auth_events_seen (
    event_id varchar(255) PRIMARY KEY,
    provider varchar(64) NOT NULL,
    subject varchar(255) NOT NULL,
    event_type varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
