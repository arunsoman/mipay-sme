-- V8: KYC master tables + workflow tables (developer-maintained, versioned).
-- One master table set defines fields, choice lists, validation, sensitivity,
-- workflow and providers for every (country, licence_type, subject_type) profile.
-- The system behaves from this master: KycMasterValidator checks every published
-- profile at boot and the app refuses to start on a broken master.
-- (The published-production target of this schema is the kyc-service DB; core
-- keeps it until the service split — the core implementation is interim.)

-- ---------------------------------------------------------------- master
-- 1) kyc_schema_versions first (profiles reference it): published = immutable
create table if not exists kyc_schema_versions (
    id           varchar(36) primary key,
    code         varchar(100) not null,
    version      integer not null,
    status       varchar(16) not null check (status in ('DRAFT','PUBLISHED','RETIRED')),
    published_at timestamp(6) with time zone,
    constraint uq_kyc_schema_code_version unique (code, version)
);
create index if not exists ix_kyc_schema_status on kyc_schema_versions (status);

-- 2) kyc_sections: a section of the form, within a schema version
create table if not exists kyc_sections (
    id                varchar(36) primary key,
    schema_version_id varchar(36) not null references kyc_schema_versions(id),
    key               varchar(64) not null,
    title             varchar(200) not null,
    step              integer not null default 0,
    kind              varchar(16) not null check (kind in ('OBJECT','LIST','DOCUMENTS')),
    min_items         integer,
    max_items         integer,
    visible_when      jsonb,
    sort              integer not null default 0,
    constraint uq_kyc_section_key unique (schema_version_id, key)
);
create index if not exists ix_kyc_sections_schema on kyc_sections (schema_version_id, sort);

-- 3) kyc_fields: a field within a section
create table if not exists kyc_fields (
    id                varchar(36) primary key,
    section_id        varchar(36) not null references kyc_sections(id),
    key               varchar(64) not null,
    label             varchar(200) not null,
    help_text         varchar(500),
    placeholder       varchar(200),
    sort              integer not null default 0,
    data_type         varchar(16) not null check (data_type in ('TEXT','NUMBER','MONEY','PERCENT','DATE','BOOLEAN','ENUM','MULTI_ENUM','COUNTRY','EMAIL','PHONE','URL')),
    list_code         varchar(64),
    list_parent_field varchar(64),
    required          varchar(16) not null check (required in ('ALWAYS','NEVER','WHEN')),
    required_when     jsonb,
    visible_when      jsonb,
    pattern           varchar(200),
    normalize         varchar(16) check (normalize in ('PUNCT')),  -- strip / - . and spaces, uppercase
    min_len           integer,
    max_len           integer,
    min_value         numeric(38,10),
    max_value         numeric(38,10),
    date_rule         varchar(16) check (date_rule in ('PAST','FUTURE','ANY')),
    derived_from      varchar(200),
    canonical_tag     varchar(64),
    sensitivity       varchar(16) not null default 'NONE' check (sensitivity in ('NONE','PII','SECRET')),
    mask              varchar(16) check (mask in ('LAST4','HIDE','YEAR_ONLY')),
    constraint uq_kyc_field_key unique (section_id, key)
);
create index if not exists ix_kyc_fields_section on kyc_fields (section_id, sort);
create index if not exists ix_kyc_fields_tag on kyc_fields (canonical_tag);

-- 4) kyc_rules: cross-field validation rules within a schema version
create table if not exists kyc_rules (
    id                varchar(36) primary key,
    schema_version_id varchar(36) not null references kyc_schema_versions(id),
    code              varchar(100) not null,
    phase             varchar(16) not null check (phase in ('SAVE','SUBMIT')),
    validator_key     varchar(64) not null,
    params            jsonb,
    message           varchar(500) not null,
    constraint uq_kyc_rule_code unique (schema_version_id, code)
);
create index if not exists ix_kyc_rules_schema on kyc_rules (schema_version_id, phase);

-- 5) kyc_lists / kyc_list_items: choice lists (values never deleted)
create table if not exists kyc_lists (
    code        varchar(64) primary key,
    country     varchar(2),
    description varchar(500)
);
create table if not exists kyc_list_items (
    id           varchar(36) primary key,
    list_code    varchar(64) not null references kyc_lists(code),
    value        varchar(200) not null,
    label        varchar(300) not null,
    parent_value varchar(200),
    attributes   jsonb,
    sort         integer not null default 0,
    active       boolean not null default true,
    constraint uq_kyc_list_item unique (list_code, value)
);
create index if not exists ix_kyc_list_items_list on kyc_list_items (list_code, active, sort);
create index if not exists ix_kyc_list_items_parent on kyc_list_items (list_code, parent_value);

-- 6) kyc_profiles: which rules apply to (country, licence_type, subject_type)
create table if not exists kyc_profiles (
    id                  varchar(36) primary key,
    code                varchar(100) not null unique,
    country             varchar(2) not null,
    licence_type        varchar(16) not null check (licence_type in ('PSP','EMI')),
    subject_type        varchar(16) not null check (subject_type in ('BUSINESS','INDIVIDUAL')),
    entity_type         varchar(16),
    org_type            varchar(16),
    schema_version_id   varchar(36) not null references kyc_schema_versions(id),
    workflow_key        varchar(64) not null,
    workflow_version    integer not null default 1,
    workflow_config     jsonb,
    collection_provider varchar(64) not null default 'native-form',
    check_providers     jsonb,
    risk_scorer         varchar(64) not null default 'default',
    params              jsonb,
    ui_enabled          boolean not null default false,
    active              boolean not null default true,
    effective_from      timestamp(6) with time zone not null default now()
);
create unique index if not exists uq_kyc_profile_active
    on kyc_profiles (country, licence_type, subject_type, entity_type, org_type) where active;
create index if not exists ix_kyc_profile_lookup on kyc_profiles (country, licence_type, subject_type, active);

-- 7) kyc_workflow_plugins: built-in plugin registry (populated by KycPluginRegistry at boot)
create table if not exists kyc_workflow_plugins (
    interface_key varchar(64) not null,
    plugin_key    varchar(64) not null,
    bean_name     varchar(200) not null,
    description   varchar(500),
    constraint uq_kyc_plugin unique (interface_key, plugin_key)
);

-- ---------------------------------------------------------------- workflow
-- 8) kyc_application_submissions: frozen copy of the document per submission
create table if not exists kyc_application_submissions (
    id             varchar(36) primary key,
    application_id varchar(36) not null references kyc_applications(id),
    submission_no  integer not null,
    doc            jsonb not null,
    submitted_by   varchar(36),
    submitted_at   timestamp(6) with time zone not null default now(),
    constraint uq_kyc_submission unique (application_id, submission_no)
);

-- 9) kyc_workflow_tasks: entry checks + stage approvals (inbox + history)
create table if not exists kyc_workflow_tasks (
    id             varchar(36) primary key,
    application_id varchar(36) not null references kyc_applications(id),
    submission_no  integer not null,
    task_kind      varchar(16) not null check (task_kind in ('ENTRY_CHECK','STAGE')),
    stage_key      varchar(64) not null,
    stage_seq      integer not null,
    actor_rule     jsonb not null,
    status         varchar(16) not null check (status in ('OPEN','DONE','CANCELLED')),
    outcome        varchar(16),
    acted_by       varchar(36),
    acted_at       timestamp(6) with time zone,
    note           varchar(1000),
    created_at     timestamp(6) with time zone not null default now(),
    updated_at     timestamp(6) with time zone not null default now()
);
-- invariant: one user acts on at most one task per application+submission
create unique index if not exists uq_kyc_task_actor
    on kyc_workflow_tasks (application_id, submission_no, acted_by) where acted_by is not null;
create index if not exists ix_kyc_tasks_open on kyc_workflow_tasks (application_id, submission_no, status);
create index if not exists ix_kyc_tasks_status on kyc_workflow_tasks (status, stage_key);

-- 10) kyc_verifications: one row per provider check
create table if not exists kyc_verifications (
    id             varchar(36) primary key,
    application_id varchar(36) not null references kyc_applications(id),
    submission_no  integer not null,
    subject        varchar(120),            -- e.g. 'org', 'ubos[1]'
    check_type     varchar(32) not null,    -- IDENTITY, SANCTIONS, PEP, ADVERSE_MEDIA, ...
    provider       varchar(64) not null,
    provider_ref   varchar(120),
    status         varchar(16) not null,    -- PENDING|CLEAR|HIT|ERROR
    detail         text,                    -- normalised result details (encrypted in a later phase)
    raw_response   text,                    -- provider raw payload, encrypted when stored
    requested_at   timestamp(6) with time zone not null default now(),
    requested_by   varchar(36),
    completed_at   timestamp(6) with time zone,
    note           varchar(500)
);
create unique index if not exists uq_kyc_verification
    on kyc_verifications (application_id, submission_no, check_type, (coalesce(subject, '')));
create index if not exists ix_kyc_verifications_app on kyc_verifications (application_id, submission_no, check_type);

-- 11) kyc_provider_events: inbound webhook de-duplication (never delivered twice)
create table if not exists kyc_provider_events (
    id          varchar(36) primary key,
    provider    varchar(64) not null,
    event_id    varchar(120) not null,
    payload     text,
    received_at timestamp(6) with time zone not null default now(),
    processed   boolean not null default false,
    constraint uq_kyc_provider_event unique (provider, event_id)
);

-- 12) kyc_search_index: HMAC blind index over SECRET canonical values
create table if not exists kyc_search_index (
    application_id varchar(36) not null references kyc_applications(id),
    canonical_tag  varchar(64) not null,
    hmac           varchar(64) not null,
    constraint pk_kyc_search_index primary key (application_id, canonical_tag, hmac)
);

-- ---------------------------------------------------------------- core-side
-- 13) user_platform_functions: OPS_REVIEWER, COMPLIANCE_OFFICER, MLRO, SENIOR_APPROVER
create table if not exists user_platform_functions (
    user_id  varchar(36) not null references users(id),
    function varchar(32) not null,
    constraint pk_user_platform_functions primary key (user_id, function)
);

-- 14) extend kyc_applications with master-driven fields
alter table kyc_applications add column if not exists country varchar(2);
alter table kyc_applications add column if not exists licence_type varchar(16) check (licence_type in ('PSP','EMI'));
alter table kyc_applications add column if not exists subject_type varchar(16) check (subject_type in ('BUSINESS','INDIVIDUAL'));
alter table kyc_applications add column if not exists entity_type varchar(16);
alter table kyc_applications add column if not exists profile_id varchar(36) references kyc_profiles(id);
alter table kyc_applications add column if not exists schema_version_id varchar(36) references kyc_schema_versions(id);
alter table kyc_applications add column if not exists workflow_key varchar(64);
alter table kyc_applications add column if not exists workflow_version integer;
alter table kyc_applications add column if not exists workflow_plan jsonb;
alter table kyc_applications add column if not exists current_stage varchar(64);
alter table kyc_applications add column if not exists submission_no integer not null default 1;
alter table kyc_applications add column if not exists risk_rating varchar(16);
alter table kyc_applications add column if not exists next_review_date timestamp(6) with time zone;
alter table kyc_applications add column if not exists retain_until timestamp(6) with time zone;
create index if not exists ix_kyc_app_profile on kyc_applications (profile_id);
create index if not exists ix_kyc_app_schema on kyc_applications (schema_version_id);

-- one OPEN application per org: re-KYC later creates a new row and keeps history
alter table kyc_applications drop constraint if exists kyc_applications_org_id_key;
alter table kyc_applications drop constraint if exists uk_kyc_applications_org;
create unique index if not exists uq_kyc_app_open on kyc_applications (org_id)
    where status in ('DRAFT','ENTRY_CHECK','VERIFYING','IN_REVIEW');

-- widen the status check (H2 has no such constraint; migrations never run there)
alter table kyc_applications drop constraint if exists kyc_applications_status_check;
alter table kyc_applications drop constraint if exists chk_kyc_applications_status;
alter table kyc_applications add constraint kyc_applications_status_check
    check (status in ('DRAFT','ENTRY_CHECK','VERIFYING','IN_REVIEW','APPROVED','REJECTED','BLOCKED','WITHDRAWN'));
