-- V7: KYC applications - one JSON document per organization, stored with the
-- org's tree details (type, parent, root-first path, depth) and is_suspended
-- (active hold on the org or any ancestor; rewritten for the subtree on every
-- suspend/lift). submitted_json is the frozen copy the reviewer decides on.
create table if not exists kyc_applications (
    id               varchar(36) primary key,
    org_id           varchar(36) not null unique references organizations(id),
    org_type         varchar(16) not null
        check (org_type in ('PLATFORM','MANUFACTURER','DISTRIBUTOR','SUPPLIER','MERCHANT','LENDER')),
    org_name         varchar(255),
    parent_org_id    varchar(36),
    tree_path        varchar(1024) not null,
    depth            integer not null,
    is_suspended     boolean not null default false,
    status           varchar(16) not null
        check (status in ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
    application_json jsonb not null,
    submitted_json   jsonb,
    created_by       varchar(36),
    updated_by       varchar(36),
    submitted_by     varchar(36),
    submitted_at     timestamp(6) with time zone,
    decided_by       varchar(36),
    decided_at       timestamp(6) with time zone,
    decision_note    varchar(1000),
    version          bigint not null default 0,
    created_at       timestamp(6) with time zone not null,
    updated_at       timestamp(6) with time zone not null,
    -- maker/checker: the decider is never the submitter
    constraint kyc_applications_independence check (decided_by is null or submitted_by is null or decided_by <> submitted_by)
);
create index if not exists ix_kyc_app_status on kyc_applications (status);
create index if not exists ix_kyc_app_parent on kyc_applications (parent_org_id);
create index if not exists ix_kyc_app_suspended on kyc_applications (is_suspended) where is_suspended;
