-- V19: NA / PSP / BUSINESS v3 — the KYC form is exactly the "Required Fields for Merchant
-- Data Capture" specification (PSP Merchant Onboarding Field Specification, Oct 2026):
--   1. Business Profile & General Information
--   2. Operational & Store Details
--   3. Registered & Operational Address
--   4. Ultimate Beneficial Owners (UBO) & Key Management
--   5. Settlement & Payout Bank Account Details
-- Everything else of v2 is gone from the form: VAT number, country of registration, industry
-- vertical, trading role, turnover band, separate website / app / storefront fields, postal
-- addresses, "same as registered", UBO roles / ownership type / effective and voting
-- percentages / control basis / PIP category and position, bank branch list, currency,
-- foreign-account fields, the review step and the documents section.
-- Canonical tags stay on the fields the platform reads (legal name, registration number, TIN,
-- MCC, volume band, UBO identity, PEP, bank holder / bank / account) so screening, risk scoring,
-- uniqueness checks and the blind index keep working.
-- v1 and v2 are PUBLISHED and immutable; applications keep the version they were created on.
-- New applications use v3 (all profiles move).

-- ------------------------------------------------------------ lists (new, values never deleted)
insert into kyc_lists (code, country, description) values
('KYC_ENTITY_TYPE',  null, 'Entity type / legal structure (merchant data capture spec)'),
('KYC_ADDRESS_TYPE', null, 'Registered office or operational / store address'),
('KYC_ID_DOC_TYPE',  null, 'Government-issued identification document type'),
('KYC_ACCOUNT_TYPE', null, 'Settlement account type')
on conflict (code) do nothing;

insert into kyc_list_items (id, list_code, value, label, sort, active) values
(gen_random_uuid()::text,'KYC_ENTITY_TYPE','SOLE_PROPRIETOR','Sole Proprietorship',1,true),
(gen_random_uuid()::text,'KYC_ENTITY_TYPE','PARTNERSHIP','Partnership',2,true),
(gen_random_uuid()::text,'KYC_ENTITY_TYPE','PRIVATE_LIMITED','Private Limited / LLC',3,true),
(gen_random_uuid()::text,'KYC_ENTITY_TYPE','PUBLIC_CORPORATION','Public Corporation',4,true),
(gen_random_uuid()::text,'KYC_ENTITY_TYPE','TRUST_NGO','Trust / NGO',5,true),
(gen_random_uuid()::text,'KYC_ADDRESS_TYPE','REGISTERED','Registered office address',1,true),
(gen_random_uuid()::text,'KYC_ADDRESS_TYPE','OPERATING','Operational / store address',2,true),
(gen_random_uuid()::text,'KYC_ID_DOC_TYPE','PASSPORT','Passport',1,true),
(gen_random_uuid()::text,'KYC_ID_DOC_TYPE','NATIONAL_ID','National ID',2,true),
(gen_random_uuid()::text,'KYC_ID_DOC_TYPE','DRIVING_LICENCE','Driver''s License',3,true),
(gen_random_uuid()::text,'KYC_ACCOUNT_TYPE','CURRENT','Checking / Current',1,true),
(gen_random_uuid()::text,'KYC_ACCOUNT_TYPE','SAVINGS','Savings',2,true)
on conflict (list_code, value) do nothing;

-- ------------------------------------------------------------ schema version
insert into kyc_schema_versions (id, code, version, status, published_at)
values (gen_random_uuid()::text, 'NA-PSP-BUSINESS', 3, 'PUBLISHED', now());

-- ------------------------------------------------------------ sections
insert into kyc_sections (id, schema_version_id, key, title, step, kind, min_items, max_items, sort)
select gen_random_uuid()::text, sv.id, t.key, t.title, t.step, t.kind, t.min_items, t.max_items, t.sort
from kyc_schema_versions sv, (values
    ('businessProfile',    'Business Profile & General Information',            1, 'OBJECT', null::int, null::int, 10),
    ('operationalDetails', 'Operational & Store Details',                       2, 'OBJECT', null, null, 20),
    ('addresses',          'Registered & Operational Address',                  3, 'LIST',   1, 2,  30),
    ('ubos',               'Ultimate Beneficial Owners (UBO) & Key Management', 4, 'LIST',   1, 20, 40),
    ('bankAccounts',       'Settlement & Payout Bank Account Details',          5, 'LIST',   1, 1,  50)
) as t(key, title, step, kind, min_items, max_items, sort)
where sv.code = 'NA-PSP-BUSINESS' and sv.version = 3;

-- ------------------------------------------------------------ fields
-- shape: (section,key,label,help,sort,type,list,req,req_when,vis_when,pattern,normalize,
--         max_len,min_len,min_val,max_val,date_rule,tag,sens,mask)
insert into kyc_fields (id, section_id, key, label, help_text, sort, data_type, list_code,
                        required, required_when, visible_when, pattern, normalize, max_len, min_len,
                        min_value, max_value, date_rule, canonical_tag, sensitivity, mask)
select gen_random_uuid()::text, s.id, f.f_key, f.f_label, f.f_help, f.f_sort, f.f_type, f.f_list,
       f.f_req, f.f_req_when::jsonb, f.f_vis_when::jsonb, f.f_pattern, f.f_normalize, f.f_max_len, f.f_min_len,
       f.f_min_val, f.f_max_val, f.f_date_rule, f.f_tag, f.f_sens, f.f_mask
from kyc_sections s
join (values
  -- 1. Business Profile & General Information
  ('businessProfile','legalBusinessName','Legal Business Name','Exact registered name as listed in legal government tax registries.',10,'TEXT',null,'ALWAYS',null,null,null,null,200,2,null::numeric,null::numeric,null,'LEGAL_NAME','NONE',null),
  ('businessProfile','tradingName','Doing Business As (DBA) / Trading Name','Public-facing store or brand name displayed to customers on checkout pages and bank statements.',20,'TEXT',null,'ALWAYS',null,null,null,null,200,1,null,null,null,null,'NONE',null),
  ('businessProfile','entityType','Entity Type / Legal Structure','Sole Proprietorship, Partnership, Private Limited / LLC, Public Corporation, or Trust / NGO.',30,'ENUM','KYC_ENTITY_TYPE','ALWAYS',null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('businessProfile','registrationNumber','Business Registration / Corporate ID Number','Official registration number (e.g. CIN, Company Registration No.).',40,'TEXT',null,'ALWAYS',null,null,null,'PUNCT',32,3,null,null,null,'REG_NUMBER','NONE',null),
  ('businessProfile','incorporationDate','Date of Incorporation / Establishment','Date the business was legally formed (DD/MM/YYYY).',50,'DATE',null,'ALWAYS',null,null,null,null,null,null,null,null,'PAST',null,'NONE',null),
  ('businessProfile','taxIdentificationNumber','Tax Identification Number (TIN / EIN / GSTIN / VAT ID)','Primary tax registration identifier.',60,'TEXT',null,'ALWAYS',null,null,null,null,32,5,null,null,null,'TAX_ID','SECRET','LAST4'),
  -- 2. Operational & Store Details
  ('operationalDetails','mcc','Merchant Category Code (MCC) / Industry Vertical','4-digit classification code (e.g. Retail, SaaS, Travel, F&B).',10,'ENUM','MCC','ALWAYS',null,null,null,null,4,4,null,null,null,'MCC','NONE',null),
  ('operationalDetails','websiteOrStorefront','Website URL / Mobile App Link / Storefront Details','Digital footprint or physical store location used for products and terms verification.',20,'TEXT',null,'ALWAYS',null,null,null,null,500,3,null,null,null,null,'NONE',null),
  ('operationalDetails','expectedMonthlyVolume','Expected Monthly Processing Volume','Estimated monetary volume used to configure initial risk limits and pricing tiers.',30,'ENUM','NA_VOLUME_BAND','ALWAYS',null,null,null,null,null,null,null,null,null,'EXPECTED_MONTHLY_VOLUME','NONE',null),
  ('operationalDetails','averageTicketSize','Average Ticket Size / Transaction Value','Typical value of a single consumer transaction.',40,'MONEY',null,'ALWAYS',null,null,null,null,14,null,0,null,null,null,'NONE',null),
  -- 3. Registered & Operational Address
  ('addresses','addressType','Address','Registered office address (matching official registration documents); add an operational / store address only if it is different.',10,'ENUM','KYC_ADDRESS_TYPE','ALWAYS',null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('addresses','streetLine','Street Line',null,20,'TEXT',null,'ALWAYS',null,null,null,null,200,2,null,null,null,null,'PII',null),
  ('addresses','town','City',null,30,'TEXT',null,'ALWAYS',null,null,null,null,100,2,null,null,null,null,'NONE',null),
  ('addresses','region','State / Province',null,40,'TEXT',null,'ALWAYS',null,null,null,null,100,2,null,null,null,null,'NONE',null),
  ('addresses','postalCode','Postal Code',null,50,'TEXT',null,'ALWAYS',null,null,null,null,10,2,null,null,null,null,'NONE',null),
  ('addresses','country','Country',null,60,'COUNTRY','COUNTRY','ALWAYS',null,null,null,null,2,null,null,null,null,null,'NONE',null),
  -- 4. Ultimate Beneficial Owners (UBO) & Key Management
  ('ubos','fullLegalName','Full Legal Name','Complete legal name of Directors, Partners, or UBOs.',10,'TEXT',null,'ALWAYS',null,null,null,null,200,2,null,null,null,'UBO_NAME','PII',null),
  ('ubos','dateOfBirth','Date of Birth (DOB)',null,20,'DATE',null,'ALWAYS',null,null,null,null,null,null,null,null,'PAST','UBO_DOB','PII','HIDE'),
  ('ubos','nationality','Nationality',null,30,'COUNTRY','COUNTRY','ALWAYS',null,null,null,null,2,null,null,null,null,'UBO_NATIONALITY','NONE',null),
  ('ubos','residentialAddress','Residential Address','Current home address of key personnel.',40,'TEXT',null,'ALWAYS',null,null,null,null,300,5,null,null,null,'UBO_ADDRESS','PII','HIDE'),
  ('ubos','identificationType','Government-Issued ID: Document Type','Passport, National ID, or Driver''s License.',50,'ENUM','KYC_ID_DOC_TYPE','ALWAYS',null,null,null,null,null,null,null,null,null,'UBO_ID_TYPE','NONE',null),
  ('ubos','identificationNumber','Government-Issued ID: ID Number',null,60,'TEXT',null,'ALWAYS',null,null,null,null,32,5,null,null,null,'UBO_ID_NUMBER','SECRET','LAST4'),
  ('ubos','identificationExpiryDate','Government-Issued ID: Expiry Date','Leave empty only for a national ID without an expiry date.',70,'DATE',null,'WHEN','{"not":{"field":"item.identificationType","eq":"NATIONAL_ID"}}',null,null,null,null,null,null,null,'FUTURE','UBO_ID_EXPIRY','NONE',null),
  ('ubos','ownershipPercentage','Ownership Percentage','Shareholding or voting rights distribution (≥ 10% to 25% threshold). Enter 0 for directors without a share.',80,'PERCENT',null,'ALWAYS',null,null,null,null,6,null,0,100,null,'UBO_OWNERSHIP','NONE',null),
  ('ubos','politicallyExposedPerson','Politically Exposed Person (PEP) Declaration','Does the individual or a close associate hold public office?',90,'BOOLEAN',null,'ALWAYS',null,null,null,null,null,null,null,null,null,'UBO_PEP','NONE',null),
  -- 5. Settlement & Payout Bank Account Details
  ('bankAccounts','beneficiaryName','Beneficiary / Account Holder Name','Must strictly match the Legal Business Name or the Sole Proprietor''s name.',10,'TEXT',null,'ALWAYS',null,null,null,null,200,2,null,null,null,'BANK_BENEFICIARY','NONE',null),
  ('bankAccounts','bankCode','Bank Name',null,20,'ENUM','NA_BANK','ALWAYS',null,null,null,null,null,null,null,null,null,'BANK_CODE','NONE',null),
  ('bankAccounts','institutionCode','Branch / Institution Code','Institution identifier (e.g. IFSC, Sort Code, Routing Number, SWIFT).',30,'TEXT',null,'ALWAYS',null,null,null,null,34,3,null,null,null,'BANK_BRANCH','NONE',null),
  ('bankAccounts','accountNumber','Account Number / IBAN','Destination account for settlement payouts.',40,'TEXT',null,'ALWAYS',null,null,null,null,34,6,null,null,null,'BANK_ACCOUNT_NUMBER','SECRET','LAST4'),
  ('bankAccounts','accountType','Account Type','Checking / Current vs. Savings.',50,'ENUM','KYC_ACCOUNT_TYPE','ALWAYS',null,null,null,null,null,null,null,null,null,null,'NONE',null)
) as f(section, f_key, f_label, f_help, f_sort, f_type, f_list, f_req, f_req_when, f_vis_when,
       f_pattern, f_normalize, f_max_len, f_min_len, f_min_val, f_max_val, f_date_rule, f_tag, f_sens, f_mask)
 on s.schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 3)
and s.key = f.section;

-- ------------------------------------------------------------ rules
-- only what the specification states, plus platform-integrity uniqueness
insert into kyc_rules (id, schema_version_id, code, phase, validator_key, params, message)
select gen_random_uuid()::text, sv.id, t.code, t.phase, t.validator_key, t.params::jsonb, t.message
from kyc_schema_versions sv, (values
    ('REG_ADDRESS_EXACT_ONE','SUBMIT','LIST_COUNT',
     '{"section":"addresses","field":"addressType","value":["REGISTERED"],"min":1,"max":1}',
     'Exactly one registered office address is required.'),
    ('OPERATING_ADDRESS_AT_MOST_ONE','SUBMIT','LIST_COUNT',
     '{"section":"addresses","field":"addressType","value":["OPERATING"],"min":0,"max":1}',
     'Add at most one operational / store address.'),
    ('OWNERSHIP_SUM_MAX','SUBMIT','SUM_MAX',
     '{"section":"ubos","field":"ownershipPercentage","max":100}',
     'The combined ownership percentages cannot exceed 100%.'),
    ('BENEFICIARY_NAME_MATCH','SUBMIT','NAME_MATCH',
     '{"section":"bankAccounts","nameField":"beneficiaryName","against":["businessProfile.legalBusinessName"],"soleProprietor":{"section":"ubos","field":"fullLegalName"},"severity":"ERROR"}',
     'The account holder name must match the legal business name or the sole proprietor''s name.'),
    ('UNIQUE_REGISTRATION','SUBMIT','UNIQUE_IN_PLATFORM',
     '{"section":"businessProfile","field":"registrationNumber","tag":"REG_NUMBER","normalize":"PUNCT","severity":"ERROR"}',
     'This registration number is already used by another active organization.'),
    ('UNIQUE_TIN','SUBMIT','UNIQUE_IN_PLATFORM',
     '{"section":"businessProfile","field":"taxIdentificationNumber","tag":"TAX_ID","severity":"ERROR"}',
     'This tax identification number is already used by another active organization.'),
    ('OWNER_ID_UNIQUE','SUBMIT','UNIQUE_IN_PLATFORM',
     '{"section":"ubos","field":"identificationNumber","tag":"UBO_ID_NUMBER","severity":"WARNING"}',
     'This identity number already appears in the platform (warning).')
) as t(code, phase, validator_key, params, message)
where sv.code = 'NA-PSP-BUSINESS' and sv.version = 3;

-- ------------------------------------------------------------ profiles move to v3
-- (open applications keep their version: their documents are shaped for it)
update kyc_profiles set schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 3)
where schema_version_id in (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version in (1, 2));
