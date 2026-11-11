-- V13: NA / PSP / BUSINESS v2. v1 is PUBLISHED and therefore immutable (design §3.2),
-- so v2 is a copy of v1 with these changes:
--  * Owners and controllers: plain "involvement" chips instead of 14 legal role names.
--    The chip wording follows the entity type and lives on the UBO_ROLE list items
--    (attributes.chips), so the form stays master-driven; the stored role codes are
--    unchanged, so the 25% test, screening and rules keep working.
--  * Rare owner fields appear only when they apply (expiry only for non-national IDs,
--    ownership only for owners, effective % only for indirect holdings, voting rights only
--    when they differ, basis of control only for controllers, PIP details only for PIPs).
--  * Residential address added (design §5.2), PII / HIDE.
--  * The NA national-ID format (11 digits) applies to NATIONAL_ID only (ITEM_PATTERN rule);
--    v1 held passports to it.
--  * Operations fields show only for the org types they apply to; foreign-bank fields only
--    for FOREIGN; address details hide when "same as registered".
-- All profiles move to v2. Open DRAFT applications move with them (they are a superset of
-- v1); submitted and decided applications keep v1.

-- ------------------------------------------------------------ schema version
insert into kyc_schema_versions (id, code, version, status, published_at)
values (gen_random_uuid()::text, 'NA-PSP-BUSINESS', 2, 'PUBLISHED', now());

-- ------------------------------------------------------------ sections (copy)
insert into kyc_sections (id, schema_version_id, key, title, step, kind, min_items, max_items, visible_when, sort)
select gen_random_uuid()::text, v2.id, s.key, s.title, s.step, s.kind, s.min_items, s.max_items, s.visible_when, s.sort
from kyc_sections s
join kyc_schema_versions v1 on v1.id = s.schema_version_id and v1.code = 'NA-PSP-BUSINESS' and v1.version = 1
cross join kyc_schema_versions v2
where v2.code = 'NA-PSP-BUSINESS' and v2.version = 2;

update kyc_sections set title = 'People: owners, managers and signatories'
where key = 'ubos'
  and schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 2);

-- ------------------------------------------------------------ fields (copy)
insert into kyc_fields (id, section_id, key, label, help_text, placeholder, sort, data_type, list_code,
                        list_parent_field, required, required_when, visible_when, pattern, normalize,
                        min_len, max_len, min_value, max_value, date_rule, derived_from, canonical_tag,
                        sensitivity, mask)
select gen_random_uuid()::text, s2.id, f.key, f.label, f.help_text, f.placeholder, f.sort, f.data_type, f.list_code,
       f.list_parent_field, f.required, f.required_when, f.visible_when, f.pattern, f.normalize,
       f.min_len, f.max_len, f.min_value, f.max_value, f.date_rule, f.derived_from, f.canonical_tag,
       f.sensitivity, f.mask
from kyc_fields f
join kyc_sections s1 on s1.id = f.section_id
join kyc_schema_versions v1 on v1.id = s1.schema_version_id and v1.code = 'NA-PSP-BUSINESS' and v1.version = 1
join kyc_schema_versions v2 on v2.code = 'NA-PSP-BUSINESS' and v2.version = 2
join kyc_sections s2 on s2.schema_version_id = v2.id and s2.key = s1.key;

-- ------------------------------------------------------------ rules (copy)
insert into kyc_rules (id, schema_version_id, code, phase, validator_key, params, message)
select gen_random_uuid()::text, v2.id, r.code, r.phase, r.validator_key, r.params, r.message
from kyc_rules r
join kyc_schema_versions v1 on v1.id = r.schema_version_id and v1.code = 'NA-PSP-BUSINESS' and v1.version = 1
cross join kyc_schema_versions v2
where v2.code = 'NA-PSP-BUSINESS' and v2.version = 2;

-- helper view of v2 fields by section + key (dropped at the end)
create temporary view v2f as
select f.* , s.key as section_key
from kyc_fields f
join kyc_sections s on s.id = f.section_id
join kyc_schema_versions v on v.id = s.schema_version_id and v.code = 'NA-PSP-BUSINESS' and v.version = 2;

-- ------------------------------------------------------------ people (ubos)
-- the role field is answered with chips; its label becomes a question
update kyc_fields set label = 'How is this person involved?',
       help_text = 'Tick everything that applies. The choices follow the entity type chosen in step 1.'
where id = (select id from v2f where section_key = 'ubos' and key = 'roles');

update kyc_fields set help_text = 'As on their identity document.'
where id = (select id from v2f where section_key = 'ubos' and key = 'fullLegalName');

-- national-ID format moves to an ITEM_PATTERN rule (below); passports and permits are free text
update kyc_fields set pattern = null, help_text = 'For a Namibian national ID: 11 digits, starting with the date of birth (YYMMDD).'
where id = (select id from v2f where section_key = 'ubos' and key = 'identificationNumber');

update kyc_fields
set visible_when  = '{"all":[{"field":"item.identificationType","exists":true},{"not":{"field":"item.identificationType","eq":"NATIONAL_ID"}}]}',
    required      = 'WHEN',
    required_when = '{"all":[{"field":"item.identificationType","exists":true},{"not":{"field":"item.identificationType","eq":"NATIONAL_ID"}}]}',
    help_text     = 'Passports and permits only.'
where id = (select id from v2f where section_key = 'ubos' and key = 'identificationExpiryDate');

-- ownership: only for owners. Direct is assumed unless they say otherwise.
update kyc_fields
set visible_when = '{"any":[{"field":"item.roles","contains":"SHAREHOLDER"},{"field":"item.roles","contains":"MEMBER"},{"field":"item.roles","contains":"PARTNER"},{"field":"item.roles","contains":"PROPRIETOR"}]}',
    label = 'Share held (%)',
    help_text = 'Their percentage of the shares, members'' interest or partnership.'
where id = (select id from v2f where section_key = 'ubos' and key = 'ownershipPercentage');

update kyc_fields
set visible_when = '{"any":[{"field":"item.roles","contains":"SHAREHOLDER"},{"field":"item.roles","contains":"MEMBER"},{"field":"item.roles","contains":"PARTNER"},{"field":"item.roles","contains":"PROPRIETOR"}]}',
    required = 'NEVER', required_when = null,
    label = 'Held through another company or trust?',
    help_text = 'Leave empty for a direct holding.'
where id = (select id from v2f where section_key = 'ubos' and key = 'ownershipType');

update kyc_fields
set visible_when = '{"field":"item.ownershipType","eq":"INDIRECT"}',
    required = 'WHEN', required_when = '{"field":"item.ownershipType","eq":"INDIRECT"}',
    help_text = 'Their share multiplied down the chain, e.g. 50% of a company that holds 60% = 30%.'
where id = (select id from v2f where section_key = 'ubos' and key = 'effectiveOwnershipPercentage');

update kyc_fields
set visible_when = '{"field":"item.votingDiffers","eq":true}',
    required = 'WHEN', required_when = '{"field":"item.votingDiffers","eq":true}'
where id = (select id from v2f where section_key = 'ubos' and key = 'votingRightsPercentage');

update kyc_fields
set visible_when = '{"any":[{"field":"item.roles","contains":"CONTROLLING_PERSON"},{"field":"item.roles","contains":"SENIOR_MANAGER"}]}',
    label = 'How do they control the business?'
where id = (select id from v2f where section_key = 'ubos' and key = 'controlBasis');

update kyc_fields
set label = 'Is this person a prominent influential person (PIP)?',
    help_text = 'A senior public official or politician, or a close family member or associate of one.'
where id = (select id from v2f where section_key = 'ubos' and key = 'politicallyExposedPerson');

update kyc_fields set visible_when = '{"field":"item.politicallyExposedPerson","eq":true}'
where id in (select id from v2f where section_key = 'ubos' and key in ('pipCategory', 'pipPosition'));

-- new: voting rights differ (switch) and residential address
insert into kyc_fields (id, section_id, key, label, help_text, sort, data_type, required, visible_when, sensitivity)
select gen_random_uuid()::text, s.id, 'votingDiffers', 'Their voting rights differ from their share',
       'Only if, for example, they hold special voting shares.', 105, 'BOOLEAN', 'NEVER',
       '{"any":[{"field":"item.roles","contains":"SHAREHOLDER"},{"field":"item.roles","contains":"MEMBER"},{"field":"item.roles","contains":"PARTNER"}]}',
       'NONE'
from kyc_sections s join kyc_schema_versions v on v.id = s.schema_version_id
where v.code = 'NA-PSP-BUSINESS' and v.version = 2 and s.key = 'ubos';

insert into kyc_fields (id, section_id, key, label, help_text, sort, data_type, required, max_len,
                        canonical_tag, sensitivity, mask)
select gen_random_uuid()::text, s.id, 'residentialAddress', 'Residential address',
       'Where they live: street, town, country.', 45, 'TEXT', 'ALWAYS', 300, 'UBO_ADDRESS', 'PII', 'HIDE'
from kyc_sections s join kyc_schema_versions v on v.id = s.schema_version_id
where v.code = 'NA-PSP-BUSINESS' and v.version = 2 and s.key = 'ubos';

-- ------------------------------------------------------------ operations: by org type
update kyc_fields set visible_when = '{"context":"orgType","in":["MERCHANT","SUPPLIER"]}'
where id in (select id from v2f where section_key = 'operationalDetails' and key in ('mcc', 'expectedMonthlyVolume'));
update kyc_fields set visible_when = '{"context":"orgType","in":["MANUFACTURER","DISTRIBUTOR","SUPPLIER"]}'
where id = (select id from v2f where section_key = 'operationalDetails' and key = 'tradingRole');
update kyc_fields set visible_when = '{"context":"orgType","in":["MERCHANT"]}'
where id in (select id from v2f where section_key = 'operationalDetails' and key in ('averageTicketSize', 'storefrontDetails'));
update kyc_fields set visible_when = '{"context":"orgType","in":["MANUFACTURER","DISTRIBUTOR"]}'
where id = (select id from v2f where section_key = 'operationalDetails' and key = 'annualTurnover');

-- ------------------------------------------------------------ addresses: hide details when same as registered
update kyc_fields set visible_when = '{"not":{"field":"item.sameAsRegistered","eq":true}}'
where id in (select id from v2f where section_key = 'addresses' and key in ('streetLine', 'town', 'postalCode', 'country'));
update kyc_fields
set visible_when = '{"all":[{"not":{"field":"item.sameAsRegistered","eq":true}},{"field":"item.country","eq":"NA"}]}'
where id = (select id from v2f where section_key = 'addresses' and key = 'region');

-- ------------------------------------------------------------ bank: foreign-only fields
update kyc_fields set visible_when = '{"field":"item.bankCode","eq":"FOREIGN"}'
where id in (select id from v2f where section_key = 'bankAccounts' and key in ('iban', 'swiftBic', 'foreignBankName'));

-- ------------------------------------------------------------ rules
insert into kyc_rules (id, schema_version_id, code, phase, validator_key, params, message)
select gen_random_uuid()::text, v.id, 'NA_NATIONAL_ID_FORMAT', 'SUBMIT', 'ITEM_PATTERN',
       '{"section":"ubos","field":"identificationNumber","pattern":"^\\d{11}$","when":{"field":"item.identificationType","eq":"NATIONAL_ID"},"message":"A Namibian national ID has 11 digits."}'::jsonb,
       'A Namibian national ID has 11 digits.'
from kyc_schema_versions v where v.code = 'NA-PSP-BUSINESS' and v.version = 2;

drop view v2f;

-- ------------------------------------------------------------ role chips (shared list, additive)
-- attributes.chips: entity type -> the chip text shown for this role ("*" = any entity type).
-- attributes.more: true puts the role behind "More roles".
update kyc_list_items set attributes = coalesce(attributes, '{}'::jsonb) || t.attrs::jsonb
from (values
  ('SHAREHOLDER',        '{"chips":{"PRIVATE_COMPANY":"Owns shares","PUBLIC_COMPANY":"Owns shares","STATE_OWNED_ENTERPRISE":"Owns shares"},"owner":true}'),
  ('MEMBER',             '{"chips":{"CLOSE_CORPORATION":"Member of the CC","SECTION_21_COMPANY":"Member"},"owner":true}'),
  ('PARTNER',            '{"chips":{"PARTNERSHIP":"Partner"},"owner":true}'),
  ('PROPRIETOR',         '{"chips":{"SOLE_PROPRIETOR":"Owner (sole proprietor)"},"owner":true}'),
  ('DIRECTOR',           '{"chips":{"PRIVATE_COMPANY":"Director","PUBLIC_COMPANY":"Director","SECTION_21_COMPANY":"Director","STATE_OWNED_ENTERPRISE":"Board member"}}'),
  ('SENIOR_MANAGER',     '{"chips":{"*":"Runs the business day to day"}}'),
  ('AUTHORISED_SIGNATORY','{"chips":{"*":"Can sign for the business"}}'),
  ('SETTLOR',            '{"chips":{"TRUST":"Settlor (created the trust)"}}'),
  ('TRUSTEE',            '{"chips":{"TRUST":"Trustee"}}'),
  ('PROTECTOR',          '{"chips":{"TRUST":"Protector"}}'),
  ('BENEFICIARY',        '{"chips":{"TRUST":"Beneficiary"}}'),
  ('CONTROLLING_PERSON', '{"chips":{"*":"Controls it another way"},"more":true}'),
  ('NOMINEE',            '{"chips":{"*":"Holds a share for someone else (nominee)"},"more":true}'),
  ('NOMINATOR',          '{"chips":{"*":"Someone holds a share on their behalf"},"more":true}')
) as t(value, attrs)
where kyc_list_items.list_code = 'UBO_ROLE' and kyc_list_items.value = t.value;

-- ------------------------------------------------------------ profiles + open drafts move to v2
update kyc_profiles set schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 2)
where schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 1);

update kyc_applications set schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 2)
where status = 'DRAFT'
  and schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 1);
