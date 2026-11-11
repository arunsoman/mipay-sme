-- V4: org-hierarchy subsystem (PLATFORM > MANUFACTURER > DISTRIBUTOR > SUPPLIER > MERCHANT; LENDER hangs off PLATFORM).
-- Closure-table tree, rights as DIRECT|CEILING rows, delegated onboarding requests with a
-- second-person decision check, activation tokens (SHA-256), notification outbox WITHOUT the
-- link ([H8]), sandbox sim outbox, and supplier bank accounts generalized to org_bank_accounts.

-- 1) organizations: one row per org; entity-typed orgs carry (org_type, entity_id) 1:1 ------
create table if not exists organizations (
    id                  varchar(36) primary key,
    org_type            varchar(16) not null check (org_type in ('PLATFORM','MANUFACTURER','DISTRIBUTOR','SUPPLIER','MERCHANT','LENDER')),
    name                varchar(255) not null,
    entity_id           varchar(36),
    registration_number varchar(255),
    parent_id           varchar(36),
    status              varchar(16) not null default 'PENDING' check (status in ('PENDING','ACTIVE','REJECTED','BLOCKED')),
    onboarded_by        varchar(36),
    onboarded_at        timestamp(6) with time zone,
    created_by          varchar(36),
    created_at          timestamp(6) with time zone,
    version             bigint not null default 0,
    constraint fk_org_parent foreign key (parent_id) references organizations(id)
);

-- one org per (type, entity); a single PLATFORM root; entity orgs sit under their parent
create unique index if not exists uq_org_entity on organizations (org_type, entity_id) where entity_id is not null;
create unique index if not exists uq_org_platform on organizations (org_type) where org_type = 'PLATFORM' and entity_id is null;
create index if not exists ix_org_parent on organizations (parent_id);
create index if not exists ix_org_type_status on organizations (org_type, status);

-- 2) org_closures: transitive ancestor/descendant closure --------------------------------
create table if not exists org_closures (
    ancestor_id   varchar(36) not null,
    descendant_id varchar(36) not null,
    depth         int not null,
    constraint fk_closure_anc foreign key (ancestor_id) references organizations(id) on delete cascade,
    constraint fk_closure_desc foreign key (descendant_id) references organizations(id) on delete cascade,
    constraint pk_closure primary key (ancestor_id, descendant_id)
);
create index if not exists ix_closure_desc on org_closures (descendant_id);

-- 3) org_rights: DIRECT = may onboard itself; CEILING = subtree-wide upper bound ----------
create table if not exists org_rights (
    id          varchar(36) primary key,
    org_id      varchar(36) not null,
    target_type varchar(16) not null check (target_type in ('MANUFACTURER','DISTRIBUTOR','SUPPLIER','MERCHANT','LENDER')),
    kind        varchar(10) not null check (kind in ('DIRECT','CEILING')),
    granted_by  varchar(36),
    granted_at  timestamp(6) with time zone,
    constraint fk_rights_org foreign key (org_id) references organizations(id)
);
create unique index if not exists uq_rights on org_rights (org_id, target_type, kind);
create index if not exists ix_rights_org on org_rights (org_id);

-- 4) suspensions: existing T15 table widened for org-scoped cascading suspension -----------
alter table suspensions add column if not exists org_id varchar(36) references organizations(id);
alter table suspensions add column if not exists suspended_by_org_id varchar(36);
alter table suspensions add column if not exists lifted_by varchar(36);
-- V1 defined the check inline, so Postgres auto-named it suspensions_entity_type_check;
-- drop both spellings before widening to include LENDER and ORG
alter table suspensions drop constraint if exists chk_suspension_entity_type;
alter table suspensions drop constraint if exists suspensions_entity_type_check;
alter table suspensions add constraint chk_suspension_entity_type
    check (entity_type in ('MERCHANT','SUPPLIER','LENDER','ORG'));
create index if not exists ix_suspensions_org on suspensions (org_id, lifted_at);

-- 5) onboarding_requests (ORG_ONBOARDING + ADMIN_RECOVERY), reviewer <> requester ([M4]) ---
create table if not exists onboarding_requests (
    id                varchar(36) primary key,
    kind              varchar(20) not null default 'ORG_ONBOARDING' check (kind in ('ORG_ONBOARDING','ADMIN_RECOVERY')),
    status            varchar(16) not null default 'PENDING_REVIEW' check (status in ('PENDING_REVIEW','APPROVED','REJECTED','BLOCKED')),
    org_id            varchar(36) not null,
    parent_org_id     varchar(36),
    admin_user_id     varchar(36),
    requested_by      varchar(36) not null,
    requested_by_role varchar(36),
    requested_by_org_id varchar(36),
    reviewed_by       varchar(36),
    reviewed_at       timestamp(6) with time zone,
    note              varchar(255),
    meta              varchar(255),
    created_at        timestamp(6) with time zone,
    version           bigint not null default 0,
    constraint fk_onb_org foreign key (org_id) references organizations(id),
    constraint chk_onboarding_reviewer_not_requester check (reviewed_by is null or reviewed_by <> requested_by)
);
create index if not exists ix_onb_status on onboarding_requests (status, created_at);
create index if not exists ix_onb_org on onboarding_requests (org_id);

-- 6) activation tokens (only the SHA-256 hash is stored; 72h expiry enforced in code) -----
create table if not exists activation_tokens (
    id         varchar(36) primary key,
    token_hash varchar(64) not null unique,
    user_id    varchar(36) not null,
    issued_by  varchar(36),
    expires_at timestamp(6) with time zone not null,
    used_at    timestamp(6) with time zone,
    created_at timestamp(6) with time zone
);
create index if not exists ix_act_user on activation_tokens (user_id, used_at);

-- 7) notification outbox: NEVER carries the link or raw token ([H8])
create table if not exists notification_messages (
    id                  varchar(36) primary key,
    channel             varchar(10) not null,
    recipient           varchar(255) not null,
    event_code          varchar(40) not null,
    template_vars       varchar(512),
    activation_token_id varchar(36),
    status              varchar(16) not null default 'QUEUED',
    created_at          timestamp(6) with time zone
);

-- 8) sim outbox (sandbox-only mirror of activation tokens; [M8])
create table if not exists sim_outbox (
    id                  varchar(36) primary key,
    purpose             varchar(40) not null,
    user_id             varchar(36),
    activation_token_id varchar(36),
    raw_token           varchar(128),
    meta                varchar(255),
    created_at          timestamp(6) with time zone
);

-- 9) users: org binding + username login + activation stamp -------------------------------
alter table users add column if not exists org_id varchar(36) references organizations(id);
alter table users add column if not exists org_role varchar(16);
alter table users add column if not exists username varchar(32);
alter table users add column if not exists created_by varchar(36);
alter table users add column if not exists activated_at timestamp(6) with time zone;
create unique index if not exists uq_users_username on users (username) where username is not null;
create index if not exists ix_users_org on users (org_id);
-- V1 users.role check excludes the new tree roles; widen it. V1 defined the
-- check inline (auto-named users_role_check) so drop both spellings first.
alter table users drop constraint if exists chk_users_role;
alter table users drop constraint if exists users_role_check;
alter table users add constraint chk_users_role
    check (role in ('MERCHANT','SUPPLIER','LENDER','MANUFACTURER','DISTRIBUTOR','TPTS_OPS','MINDLABS_ADMIN'));

-- 10) generalize supplier bank accounts -> org_bank_accounts (org-scoped rows) -------------
alter table supplier_bank_accounts drop constraint if exists chk_bank_approver_not_requester;
alter table supplier_bank_accounts rename to org_bank_accounts;
alter table org_bank_accounts rename column supplier_id to org_id;
-- drop the legacy supplier-scoped foreign keys (the column now carries org ids)
do $$
declare r record;
begin
    for r in select c.conname from pg_constraint c
             join pg_class t on c.conrelid = t.oid
             where t.relname = 'org_bank_accounts' and c.contype = 'f'
               and c.conname <> 'fk_org_bank_accounts_org'
    loop
        execute format('alter table org_bank_accounts drop constraint %I', r.conname);
    end loop;
end $$;
alter table org_bank_accounts
    add constraint fk_org_bank_accounts_org foreign key (org_id) references organizations(id);
alter table org_bank_accounts
    add constraint chk_bank_approver_not_requester
    check (approved_by is null or approved_by <> requested_by);

-- 10b) entity mirrors introduced with the org subsystem (nullable; lifecycle stays in organizations)
alter table suppliers add column if not exists supplier_type varchar(255);
alter table suppliers add column if not exists owner_id_number varchar(255);
alter table suppliers add column if not exists approved_by varchar(36);
alter table suppliers add column if not exists approved_at timestamp(6) with time zone;
alter table lenders add column if not exists approval_status varchar(255)
    check (approval_status in ('PENDING','APPROVED','SUSPENDED'));

-- 11) backfill: platform root + rights + 1:1 entity orgs + closures + user bindings --------
insert into organizations (id, org_type, name, status, created_by, created_at)
select '00000000-0000-0000-0000-000000000000', 'PLATFORM', 'Platform Root', 'ACTIVE', 'system', now()
where not exists (select 1 from organizations where org_type = 'PLATFORM');

insert into org_closures (ancestor_id, descendant_id, depth)
select o.id, o.id, 0 from organizations o
on conflict do nothing;

-- root carries explicit DIRECT + CEILING rows for all 5 child types ([H7])
insert into org_rights (id, org_id, target_type, kind, granted_by, granted_at)
select 'org-right-root-' || t || '-' || k, '00000000-0000-0000-0000-000000000000', t, k, 'system', now()
from unnest(array['MANUFACTURER','DISTRIBUTOR','SUPPLIER','MERCHANT','LENDER']) as t,
     unnest(array['DIRECT','CEILING']) as k
where not exists (select 1 from org_rights
                  where org_id = '00000000-0000-0000-0000-000000000000' and target_type = t and kind = k);

-- suppliers / merchants / lenders get a 1:1 ACTIVE org under the root, lifecycle mirrors
insert into organizations (id, org_type, name, entity_id, parent_id, status, created_by, created_at)
select 'org-sup-' || s.id, 'SUPPLIER', s.business_name, s.id, '00000000-0000-0000-0000-000000000000',
       case when s.approval_status = 'SUSPENDED' then 'PENDING' else 'ACTIVE' end,
       'system', now()
from suppliers s
where not exists (select 1 from organizations where entity_id = s.id and org_type = 'SUPPLIER');

insert into organizations (id, org_type, name, entity_id, parent_id, status, created_by, created_at)
select 'org-mer-' || m.id, 'MERCHANT', m.business_name, m.id, '00000000-0000-0000-0000-000000000000',
       case when m.approval_status = 'SUSPENDED' then 'PENDING' else 'ACTIVE' end,
       'system', now()
from merchants m
where not exists (select 1 from organizations where entity_id = m.id and org_type = 'MERCHANT');

insert into organizations (id, org_type, name, entity_id, parent_id, status, created_by, created_at)
select 'org-len-' || l.id, 'LENDER', l.name, l.id, '00000000-0000-0000-0000-000000000000', 'ACTIVE', 'system', now()
from lenders l
where not exists (select 1 from organizations where entity_id = l.id and org_type = 'LENDER');

-- closure: root -> every org directly under it (depth 1); self rows already inserted above
insert into org_closures (ancestor_id, descendant_id, depth)
select '00000000-0000-0000-0000-000000000000', o.id, 1 from organizations o
where o.parent_id = '00000000-0000-0000-0000-000000000000'
on conflict do nothing;

-- suppliers may onboard merchants: explicit right mirrors of the rank rules
insert into org_rights (id, org_id, target_type, kind, granted_by, granted_at)
select 'org-right-sup-' || s.id || '-MERCHANT-DIRECT', 'org-sup-' || s.id, 'MERCHANT', 'DIRECT', 'system', now()
from suppliers s
where not exists (select 1 from org_rights
                  where org_id = 'org-sup-' || s.id and target_type = 'MERCHANT' and kind = 'DIRECT');

-- bind entity owners + platform staff to their orgs as ADMIN; usernames for staff logins
update users set org_id = 'org-sup-' || s.id, org_role = 'ADMIN'
from suppliers s
where users.id = s.user_id and users.org_id is null;
update users set org_id = 'org-mer-' || m.id, org_role = 'ADMIN'
from merchants m
where users.id = m.user_id and users.org_id is null;
update users set org_id = 'org-len-' || l.id, org_role = 'ADMIN'
from lenders l
where users.id = l.user_id and users.org_id is null;
update users set org_id = '00000000-0000-0000-0000-000000000000', org_role = 'ADMIN'
where users.role in ('TPTS_OPS','MINDLABS_ADMIN') and users.org_id is null;

update users set username = 'ops-admin-ops'
where role = 'TPTS_OPS' and username is null;
update users set username = 'ops-admin-mindlabs'
where role = 'MINDLABS_ADMIN' and username is null;

-- bank-account rows re-pointed from supplier ids to their org ids
update org_bank_accounts b
set org_id = 'org-sup-' || b.org_id
where b.org_id in (select id from suppliers)
  and not exists (select 1 from organizations o where o.id = b.org_id and o.org_type = 'SUPPLIER');

-- entity-keyed suspensions mirrored onto their org rows (subtree visibility) ---------------
update suspensions se
set org_id = 'org-mer-' || se.entity_id
where se.entity_type = 'MERCHANT' and se.org_id is null
  and exists (select 1 from organizations o where o.id = 'org-mer-' || se.entity_id);
update suspensions se
set org_id = 'org-sup-' || se.entity_id
where se.entity_type = 'SUPPLIER' and se.org_id is null
  and exists (select 1 from organizations o where o.id = 'org-sup-' || se.entity_id);
update suspensions se
set org_id = 'org-len-' || se.entity_id
where se.entity_type = 'LENDER' and se.org_id is null
  and exists (select 1 from organizations o where o.id = 'org-len-' || se.entity_id);
