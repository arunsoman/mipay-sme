-- V10: migrate existing V7 KYC applications onto the master (§10 of the design).
-- 1. Enum/key renames inside the JSON (old value -> new value per the mapping table);
-- 2. status mapping (SUBMITTED -> IN_REVIEW with the default ops-review plan);
-- 3. submitted_json becomes submission 1 (kyc_application_submissions);
-- 4. pending ORG_ONBOARDING requests become KYC applications with an ops-review task;
-- 5. TPTS_OPS users get OPS_REVIEWER (one compliance user is ensured at boot);
-- 6. unmapped region values flag the application needs_upgrade.
-- The SECRET encryption backfill (KycEncryptBackfill) runs in Java at boot.

-- ---- 1) value renames inside the documents
update kyc_applications set application_json = replace(replace(replace(replace(replace(
    application_json::text,
    '"PRIVATE_LIMITED"', '"PRIVATE_COMPANY"'),
    '"PUBLIC_LIMITED"',  '"PUBLIC_COMPANY"'),
    '"SOLE_PROPRIETORSHIP"', '"SOLE_PROPRIETOR"'),
    '"CHECKING"',        '"CURRENT"'),
    '"DRIVING_LICENSE"', '"DRIVING_LICENCE"')::jsonb
where application_json::text like '%"PRIVATE_LIMITED"%' or application_json::text like '%"CHECKING"%'
   or application_json::text like '%"SOLE_PROPRIETORSHIP"%' or application_json::text like '%"DRIVING_LICENSE"%'
   or application_json::text like '%"PUBLIC_LIMITED"%';

update kyc_applications set submitted_json = replace(replace(replace(replace(replace(
    submitted_json::text,
    '"PRIVATE_LIMITED"', '"PRIVATE_COMPANY"'),
    '"PUBLIC_LIMITED"',  '"PUBLIC_COMPANY"'),
    '"SOLE_PROPRIETORSHIP"', '"SOLE_PROPRIETOR"'),
    '"CHECKING"',        '"CURRENT"'),
    '"DRIVING_LICENSE"', '"DRIVING_LICENCE"')::jsonb
where submitted_json is not null
  and (submitted_json::text like '%"PRIVATE_LIMITED"%' or submitted_json::text like '%"CHECKING"%'
   or submitted_json::text like '%"SOLE_PROPRIETORSHIP"%' or submitted_json::text like '%"DRIVING_LICENSE"%'
   or submitted_json::text like '%"PUBLIC_LIMITED"%');

-- ---- 2) key renames: city -> town, state -> region
update kyc_applications set application_json = replace(replace(application_json::text,
    '"city":',  '"town":'), '"state":', '"region":')::jsonb
where application_json::text like '%"city"%' or application_json::text like '%"state"%';
update kyc_applications set submitted_json = replace(replace(submitted_json::text,
    '"city":',  '"town":'), '"state":', '"region":')::jsonb
where submitted_json is not null
  and (submitted_json::text like '%"city"%' or submitted_json::text like '%"state"%');

-- ---- master-driven columns + needs_upgrade flag
alter table kyc_applications add column if not exists needs_upgrade boolean not null default false;

-- resolve profile + schema by org type (existing rows are all NA/PSP/BUSINESS)
update kyc_applications a
set country = 'NA', licence_type = 'PSP', subject_type = 'BUSINESS',
    entity_type = (case when a.application_json::text like '%"entityType": "STATE_OWNED_ENTERPRISE"%'
                        then 'STATE_OWNED_ENTERPRISE' else null end),
    profile_id = p.id,
    schema_version_id = p.schema_version_id,
    workflow_key = p.workflow_key,
    workflow_version = p.workflow_version
from organizations o
join kyc_profiles p on p.org_type = o.org_type and p.active and p.country = 'NA' and p.licence_type = 'PSP'
where a.org_id = o.id;

-- SUBMITTED -> IN_REVIEW with the default ops-review plan (§10)
update kyc_applications
set status = 'IN_REVIEW',
    current_stage = 'ops-review',
    submission_no = 1,
    workflow_plan = '{"approvalRequired":true,"entryCheck":"NONE","verification":[],
                      "stages":[{"key":"ops-review","seq":1,
                                 "actor":{"relation":"PLATFORM","function":"OPS_REVIEWER"},
                                 "outcomes":["APPROVE","RETURN","REJECT","BLOCK"]}],
                      "contributors":[]}'::jsonb
where status = 'SUBMITTED';

-- ---- 3) submitted_json -> submission 1
insert into kyc_application_submissions (id, application_id, submission_no, doc, submitted_by, submitted_at)
select gen_random_uuid()::text, a.id, 1, a.submitted_json, a.submitted_by, coalesce(a.submitted_at, now())
from kyc_applications a
where a.submitted_json is not null
on conflict (application_id, submission_no) do nothing;

-- unmapped regions flag needs_upgrade (values that don't match the NA_REGION list)
update kyc_applications a set needs_upgrade = true
where a.status in ('DRAFT','IN_REVIEW')
  and exists (
    select 1
    from jsonb_array_elements(a.application_json -> 'addresses') ad
    where (ad ->> 'region') is not null
      and not exists (
        select 1 from kyc_list_items li
        where li.list_code = 'NA_REGION' and li.active
          and lower(li.value) = lower(ad ->> 'region')));

-- ---- 4) pending ORG_ONBOARDING requests -> KYC applications with an ops-review task
insert into kyc_applications (id, org_id, org_type, org_name, tree_path, depth, is_suspended,
                              status, application_json, submitted_json, submitted_by, submitted_at,
                              country, licence_type, subject_type, profile_id, schema_version_id,
                              workflow_key, workflow_version, workflow_plan, current_stage,
                              submission_no, created_by, created_at, version, updated_at)
select gen_random_uuid()::text,
       o.id, o.org_type, o.name,
       coalesce((select string_agg(anc.id, '/' order by cl.depth)
                 from org_closures cl join organizations anc on anc.id = cl.ancestor_id
                 where cl.descendant_id = o.id), o.id),
       coalesce((select max(cl.depth) from org_closures cl where cl.descendant_id = o.id), 0),
       coalesce((select bool_or(lifted_at is null) from suspensions s
                 join org_closures cl on cl.ancestor_id = s.org_id
                 where cl.descendant_id = o.id), false),
       'IN_REVIEW', '{}'::jsonb, null, null, null,
       'NA', 'PSP', 'BUSINESS', p.id, p.schema_version_id, p.workflow_key, p.workflow_version,
       '{"approvalRequired":true,"entryCheck":"NONE","verification":[],
         "stages":[{"key":"ops-review","seq":1,
                    "actor":{"relation":"PLATFORM","function":"OPS_REVIEWER"},
                    "outcomes":["APPROVE","RETURN","REJECT","BLOCK"]}],
         "contributors":[]}'::jsonb,
       'ops-review', 1, r.requested_by, now(), 0, coalesce(now(), now())
from onboarding_requests r
join organizations o on o.id = r.org_id
join kyc_profiles p on p.org_type = o.org_type and p.active and p.country = 'NA' and p.licence_type = 'PSP'
where r.kind = 'ORG_ONBOARDING' and r.status = 'PENDING_REVIEW'
  and not exists (select 1 from kyc_applications a where a.org_id = o.id)
on conflict do nothing;

-- ops-review task for every IN_REVIEW application that has no open task yet
insert into kyc_workflow_tasks (id, application_id, submission_no, task_kind, stage_key, stage_seq, actor_rule, status)
select gen_random_uuid()::text, a.id, coalesce(a.submission_no, 1), 'STAGE', 'ops-review', 1,
       '{"relation":"PLATFORM","function":"OPS_REVIEWER"}'::jsonb, 'OPEN'
from kyc_applications a
where a.status = 'IN_REVIEW'
  and not exists (select 1 from kyc_workflow_tasks t
                  where t.application_id = a.id and t.submission_no = coalesce(a.submission_no, 1)
                    and t.stage_key = 'ops-review' and t.status in ('OPEN','DONE'));

-- ---- 5) platform functions
insert into user_platform_functions (user_id, function)
select u.id, 'OPS_REVIEWER' from users u
where u.role = 'TPTS_OPS'
on conflict do nothing;
