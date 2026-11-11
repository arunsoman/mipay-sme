-- V9: KYC master data — NA / PSP / BUSINESS v1 (ui_enabled = true), one profile
-- per org type. Sources: response1.md + design §5 (Financial Intelligence Act
-- 13 of 2012 as amended; FIC guidance; BIPA; NamRA; BoN branch codes — sandbox
-- list dated 1 June 2016, confirm with banks before production).
-- On H2 (tests) migrations never run: KycMasterSeed seeds the same dataset.
-- Conditions follow the §3.5 grammar: {"field":..,"eq"/"in":..} , all/any/not,
-- list-item-relative (item.x / ubos[].x), and "context" (orgType/licenceType/country).

-- ---------------------------------------------------------------- lists
insert into kyc_lists (code, country, description) values
('NA_ENTITY_TYPE',   null, 'Namibian entity types (Companies Act 28 of 2004 / Close Corporations Act 26 of 1988)'),
('COUNTRY',          null, 'ISO 3166-1 alpha-2 selectable countries'),
('NA_REGION',        'NA', 'Namibian regions'),
('ADDRESS_TYPE',     null, 'Address type'),
('ID_TYPE',          null, 'Identity document type'),
('UBO_ROLE',         null, 'Beneficial owner / controller role (multi-select)'),
('OWNERSHIP_TYPE',   null, 'Direct or indirect holding'),
('CONTROL_BASIS',    null, 'Basis of control when no owner reaches the 25% threshold (FIA Schedule 5)'),
('PIP_CATEGORY',     null, 'Prominent influential person category (FIA Schedule 6)'),
('NA_BANK',          'NA', 'Authorised Namibian banking institutions'),
('NA_BANK_BRANCH',   'NA', 'Branch codes per bank (sandbox list dated 1 June 2016)'),
('NA_ACCOUNT_TYPE',  null, 'Namibian bank account type'),
('CURRENCY',         null, 'Currency'),
('MCC',              null, 'ISO 18245 merchant category codes (subset)'),
('INDUSTRY_VERTICAL', null, 'Industry vertical'),
('NA_VOLUME_BAND',   'NA', 'Expected monthly volume band (N$/month; internal risk tier, FIA s.20A)'),
('TRADING_ROLE',     null, 'Trading role'),
('TURNOVER_BAND',    null, 'Annual turnover band (N$/year)')
on conflict (code) do nothing;

insert into kyc_list_items (id, list_code, value, label, sort, active) values
(gen_random_uuid()::text,'NA_ENTITY_TYPE','SOLE_PROPRIETOR','Sole proprietor',1,true),
(gen_random_uuid()::text,'NA_ENTITY_TYPE','PARTNERSHIP','Partnership',2,true),
(gen_random_uuid()::text,'NA_ENTITY_TYPE','CLOSE_CORPORATION','Close corporation (CC)',3,true),
(gen_random_uuid()::text,'NA_ENTITY_TYPE','PRIVATE_COMPANY','Private company ((Pty) Ltd)',4,true),
(gen_random_uuid()::text,'NA_ENTITY_TYPE','PUBLIC_COMPANY','Public company (Ltd)',5,true),
(gen_random_uuid()::text,'NA_ENTITY_TYPE','SECTION_21_COMPANY','Section 21 (not for gain)',6,true),
(gen_random_uuid()::text,'NA_ENTITY_TYPE','TRUST','Trust',7,true),
(gen_random_uuid()::text,'NA_ENTITY_TYPE','STATE_OWNED_ENTERPRISE','State-owned enterprise',8,true),
(gen_random_uuid()::text,'COUNTRY','NA','Namibia',1,true),
(gen_random_uuid()::text,'COUNTRY','ZA','South Africa',2,true),
(gen_random_uuid()::text,'COUNTRY','BW','Botswana',3,true),
(gen_random_uuid()::text,'COUNTRY','SZ','Eswatini',4,true),
(gen_random_uuid()::text,'COUNTRY','LS','Lesotho',5,true),
(gen_random_uuid()::text,'COUNTRY','ZM','Zambia',6,true),
(gen_random_uuid()::text,'COUNTRY','ZW','Zimbabwe',7,true),
(gen_random_uuid()::text,'COUNTRY','MZ','Mozambique',8,true),
(gen_random_uuid()::text,'COUNTRY','AO','Angola',9,true),
(gen_random_uuid()::text,'COUNTRY','OTHER','Other',99,true),
(gen_random_uuid()::text,'NA_REGION','ERONGO','Erongo',1,true),
(gen_random_uuid()::text,'NA_REGION','HARDAP','Hardap',2,true),
(gen_random_uuid()::text,'NA_REGION','KHARAS','//Kharas',3,true),
(gen_random_uuid()::text,'NA_REGION','KAVANGO_EAST','Kavango East',4,true),
(gen_random_uuid()::text,'NA_REGION','KAVANGO_WEST','Kavango West',5,true),
(gen_random_uuid()::text,'NA_REGION','KHOMAS','Khomas',6,true),
(gen_random_uuid()::text,'NA_REGION','KUNENE','Kunene',7,true),
(gen_random_uuid()::text,'NA_REGION','OHANGWENA','Ohangwena',8,true),
(gen_random_uuid()::text,'NA_REGION','OMAHEKE','Omaheke',9,true),
(gen_random_uuid()::text,'NA_REGION','OMUSATI','Omusati',10,true),
(gen_random_uuid()::text,'NA_REGION','OSHANA','Oshana',11,true),
(gen_random_uuid()::text,'NA_REGION','OSHIKOTO','Oshikoto',12,true),
(gen_random_uuid()::text,'NA_REGION','OTJOZONDJUPA','Otjozondjupa',13,true),
(gen_random_uuid()::text,'NA_REGION','ZAMBEZI','Zambezi',14,true),
(gen_random_uuid()::text,'ADDRESS_TYPE','REGISTERED','Registered address',1,true),
(gen_random_uuid()::text,'ADDRESS_TYPE','OPERATING','Operating address',2,true),
(gen_random_uuid()::text,'ADDRESS_TYPE','POSTAL','Postal address',3,true),
(gen_random_uuid()::text,'ID_TYPE','NATIONAL_ID','National ID',1,true),
(gen_random_uuid()::text,'ID_TYPE','PASSPORT','Passport',2,true),
(gen_random_uuid()::text,'ID_TYPE','DRIVING_LICENCE','Driving licence',3,true),
(gen_random_uuid()::text,'ID_TYPE','RESIDENCE_PERMIT','Residence permit',4,true),
(gen_random_uuid()::text,'ID_TYPE','WORK_PERMIT','Work permit',5,true),
(gen_random_uuid()::text,'UBO_ROLE','SHAREHOLDER','Shareholder',1,true),
(gen_random_uuid()::text,'UBO_ROLE','MEMBER','Close corporation member',2,true),
(gen_random_uuid()::text,'UBO_ROLE','PARTNER','Partner',3,true),
(gen_random_uuid()::text,'UBO_ROLE','PROPRIETOR','Sole proprietor',4,true),
(gen_random_uuid()::text,'UBO_ROLE','DIRECTOR','Director',5,true),
(gen_random_uuid()::text,'UBO_ROLE','SENIOR_MANAGER','Senior management official',6,true),
(gen_random_uuid()::text,'UBO_ROLE','SETTLOR','Settlor',7,true),
(gen_random_uuid()::text,'UBO_ROLE','TRUSTEE','Trustee',8,true),
(gen_random_uuid()::text,'UBO_ROLE','PROTECTOR','Protector',9,true),
(gen_random_uuid()::text,'UBO_ROLE','BENEFICIARY','Beneficiary',10,true),
(gen_random_uuid()::text,'UBO_ROLE','NOMINEE','Nominee shareholder / director',11,true),
(gen_random_uuid()::text,'UBO_ROLE','NOMINATOR','Nominator (person in whose name the nominee holds)',12,true),
(gen_random_uuid()::text,'UBO_ROLE','AUTHORISED_SIGNATORY','Authorised signatory',13,true),
(gen_random_uuid()::text,'UBO_ROLE','CONTROLLING_PERSON','Controlling person',14,true),
(gen_random_uuid()::text,'OWNERSHIP_TYPE','DIRECT','Direct holding',1,true),
(gen_random_uuid()::text,'OWNERSHIP_TYPE','INDIRECT','Indirect holding',2,true),
(gen_random_uuid()::text,'CONTROL_BASIS','SENIOR_MANAGEMENT','Control via senior management official',1,true),
(gen_random_uuid()::text,'CONTROL_BASIS','APPOINTS_BOARD_MAJORITY','Appoints or removes majority of the board',2,true),
(gen_random_uuid()::text,'CONTROL_BASIS','MATERIAL_INFLUENCE','Other material influence',3,true),
(gen_random_uuid()::text,'CONTROL_BASIS','ECONOMIC_BENEFIT','Benefits economically otherwise',4,true),
(gen_random_uuid()::text,'CONTROL_BASIS','OTHER_CONTROL','Other control basis',5,true),
(gen_random_uuid()::text,'PIP_CATEGORY','HEAD_OF_STATE_GOVERNMENT','Head of state / head of government',1,true),
(gen_random_uuid()::text,'PIP_CATEGORY','MINISTER_DEPUTY','Government minister / deputy minister',2,true),
(gen_random_uuid()::text,'PIP_CATEGORY','SENIOR_POLITICIAN','Senior politician',3,true),
(gen_random_uuid()::text,'PIP_CATEGORY','MEMBER_OF_PARLIAMENT','Member of parliament',4,true),
(gen_random_uuid()::text,'PIP_CATEGORY','SENIOR_GOVERNMENT_OFFICIAL','Senior government official',5,true),
(gen_random_uuid()::text,'PIP_CATEGORY','POLITICAL_PARTY_GOVERNING_BODY','Member of governing body of political party',6,true),
(gen_random_uuid()::text,'PIP_CATEGORY','LOCAL_REGIONAL_COUNCILLOR','Member of local or regional council',7,true),
(gen_random_uuid()::text,'PIP_CATEGORY','PUBLIC_ENTERPRISE_EXECUTIVE','Senior executive of public-owned enterprise',8,true),
(gen_random_uuid()::text,'PIP_CATEGORY','JUDICIAL_OFFICER','Judicial officer',9,true),
(gen_random_uuid()::text,'PIP_CATEGORY','AMBASSADOR','Ambassador / high-ranking diplomat',10,true),
(gen_random_uuid()::text,'PIP_CATEGORY','SENIOR_MILITARY_OFFICER','High-ranking officer of armed forces',11,true),
(gen_random_uuid()::text,'PIP_CATEGORY','TRADITIONAL_LEADER','Member of Traditional Authority',12,true),
(gen_random_uuid()::text,'PIP_CATEGORY','RELIGIOUS_LEADER','Member of governing body of religious denomination',13,true),
(gen_random_uuid()::text,'PIP_CATEGORY','PRIVATE_SECTOR_SENIOR_EXECUTIVE','Senior executive of company / body corporate',14,true),
(gen_random_uuid()::text,'PIP_CATEGORY','INTERNATIONAL_ORG_EXECUTIVE','Senior executive of international organisation',15,true),
(gen_random_uuid()::text,'PIP_CATEGORY','FOREIGN_PIP','Foreign prominent influential person',16,true),
(gen_random_uuid()::text,'PIP_CATEGORY','FAMILY_MEMBER','Family member of a PIP',17,true),
(gen_random_uuid()::text,'PIP_CATEGORY','CLOSE_ASSOCIATE','Close associate of a PIP',18,true),
(gen_random_uuid()::text,'NA_BANK','BANK_WINDHOEK','Bank Windhoek',1,true),
(gen_random_uuid()::text,'NA_BANK','FNB_NAMIBIA','First National Bank Namibia',2,true),
(gen_random_uuid()::text,'NA_BANK','STANDARD_BANK_NA','Standard Bank Namibia',3,true),
(gen_random_uuid()::text,'NA_BANK','NEDBANK_NA','Nedbank Namibia',4,true),
(gen_random_uuid()::text,'NA_BANK','BANK_BIC','Bank BIC Namibia',5,true),
(gen_random_uuid()::text,'NA_BANK','LETSHEGO','Letshego Bank Namibia',6,true),
(gen_random_uuid()::text,'NA_BANK','FOREIGN','Foreign bank account',99,true),
(gen_random_uuid()::text,'NA_ACCOUNT_TYPE','CURRENT','Current / cheque',1,true),
(gen_random_uuid()::text,'NA_ACCOUNT_TYPE','SAVINGS','Savings',2,true),
(gen_random_uuid()::text,'NA_ACCOUNT_TYPE','TRANSMISSION','Transmission',3,true),
(gen_random_uuid()::text,'CURRENCY','NAD','Namibian dollar (N$)',1,true),
(gen_random_uuid()::text,'CURRENCY','ZAR','South African rand',2,true),
(gen_random_uuid()::text,'CURRENCY','USD','US dollar',3,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','RETAIL','Retail',1,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','FOOD_BEVERAGE','Food and beverage',2,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','HOSPITALITY','Hospitality',3,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','TRANSPORT','Transport',4,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','HEALTH','Health',5,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','EDUCATION','Education',6,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','NON_PROFIT','Non-profit',7,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','WHOLESALE_DISTRIBUTION','Wholesale distribution',8,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','PROFESSIONAL_SERVICES','Professional services',9,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','MANUFACTURING','Manufacturing',10,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','AGRICULTURE','Agriculture',11,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','CONSTRUCTION','Construction',12,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','TELECOMS_UTILITIES','Telecoms and utilities',13,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','GOVERNMENT','Government',14,true),
(gen_random_uuid()::text,'INDUSTRY_VERTICAL','OTHER','Other',99,true),
(gen_random_uuid()::text,'NA_VOLUME_BAND','BELOW_50K','Under N$50,000 per month',1,true),
(gen_random_uuid()::text,'NA_VOLUME_BAND','50K_250K','N$50,000 to N$250,000 per month',2,true),
(gen_random_uuid()::text,'NA_VOLUME_BAND','250K_1M','N$250,000 to N$1 million per month',3,true),
(gen_random_uuid()::text,'NA_VOLUME_BAND','1M_5M','N$1 million to N$5 million per month',4,true),
(gen_random_uuid()::text,'NA_VOLUME_BAND','OVER_5M','Over N$5 million per month',5,true),
(gen_random_uuid()::text,'TRADING_ROLE','MANUFACTURER','Manufacturer',1,true),
(gen_random_uuid()::text,'TRADING_ROLE','IMPORTER','Importer',2,true),
(gen_random_uuid()::text,'TRADING_ROLE','WHOLESALER','Wholesaler',3,true),
(gen_random_uuid()::text,'TRADING_ROLE','DISTRIBUTOR','Distributor',4,true),
(gen_random_uuid()::text,'TRADING_ROLE','RETAILER','Retailer',5,true),
(gen_random_uuid()::text,'TRADING_ROLE','SERVICE_PROVIDER','Service provider',6,true),
(gen_random_uuid()::text,'TURNOVER_BAND','UNDER_1M','Under N$1,000,000 per year',1,true),
(gen_random_uuid()::text,'TURNOVER_BAND','1M_10M','N$1,000,000 to N$10,000,000 per year',2,true),
(gen_random_uuid()::text,'TURNOVER_BAND','10M_50M','N$10,000,000 to N$50,000,000 per year',3,true),
(gen_random_uuid()::text,'TURNOVER_BAND','OVER_50M','Over N$50,000,000 per year',4,true)
on conflict (list_code, value) do nothing;

-- NA_BANK_BRANCH (parent = bank; attributes.branchCode ^\d{5,6}$)
insert into kyc_list_items (id, list_code, value, label, parent_value, attributes, sort, active) values
(gen_random_uuid()::text,'NA_BANK_BRANCH','WINDHOEK','Windhoek','BANK_WINDHOEK','{"branchCode":"481972"}',1,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','MAERUA_MALL','Maerua Mall','BANK_WINDHOEK','{"branchCode":"483872"}',2,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','SWAKOPMUND','Swakopmund','BANK_WINDHOEK','{"branchCode":"481772"}',3,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','KATIMA_MULILO','Katima Mulilo','BANK_WINDHOEK','{"branchCode":"482273"}',4,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','WINDHOEK_MAIN','Windhoek Main','FNB_NAMIBIA','{"branchCode":"280172"}',5,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','FNB_MAERUA','Maerua Mall','FNB_NAMIBIA','{"branchCode":"282273"}',6,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','FNB_SWAKOPMUND','Swakopmund','FNB_NAMIBIA','{"branchCode":"280472"}',7,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','SMB_WINDHOEK','Windhoek','STANDARD_BANK_NA','{"branchCode":"082372"}',8,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','SMB_SWAKOPMUND','Swakopmund','STANDARD_BANK_NA','{"branchCode":"082172"}',9,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','NED_WINDHOEK_SOUTH','Windhoek South','NEDBANK_NA','{"branchCode":"461629"}',10,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','NED_SWAKOPMUND','Swakopmund','NEDBANK_NA','{"branchCode":"461052"}',11,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','BW_UNIVERSAL','Bank Windhoek (universal)','BANK_WINDHOEK','{"branchCode":"482872"}',20,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','FNB_UNIVERSAL','FNB Namibia (universal)','FNB_NAMIBIA','{"branchCode":"282672"}',21,true),
(gen_random_uuid()::text,'NA_BANK_BRANCH','SMB_UNIVERSAL','Standard Bank Namibia (universal)','STANDARD_BANK_NA','{"branchCode":"087373"}',22,true)
on conflict (list_code, value) do nothing;

-- MCC (attributes: vertical, risk)
insert into kyc_list_items (id, list_code, value, label, attributes, sort, active) values
(gen_random_uuid()::text,'MCC','5411','Grocery stores, supermarkets','{"vertical":"RETAIL","risk":"LOW"}',1,true),
(gen_random_uuid()::text,'MCC','5812','Eating places, restaurants','{"vertical":"FOOD_BEVERAGE","risk":"LOW"}',2,true),
(gen_random_uuid()::text,'MCC','5813','Drinking places','{"vertical":"FOOD_BEVERAGE","risk":"MEDIUM"}',3,true),
(gen_random_uuid()::text,'MCC','5541','Service stations','{"vertical":"RETAIL","risk":"LOW"}',4,true),
(gen_random_uuid()::text,'MCC','5311','Department stores','{"vertical":"RETAIL","risk":"LOW"}',5,true),
(gen_random_uuid()::text,'MCC','5651','Clothing stores','{"vertical":"RETAIL","risk":"LOW"}',6,true),
(gen_random_uuid()::text,'MCC','5999','Specialty retail stores','{"vertical":"RETAIL","risk":"MEDIUM"}',7,true),
(gen_random_uuid()::text,'MCC','4121','Taxicabs and limousines','{"vertical":"TRANSPORT","risk":"MEDIUM"}',8,true),
(gen_random_uuid()::text,'MCC','7011','Hotels and lodging','{"vertical":"HOSPITALITY","risk":"LOW"}',9,true),
(gen_random_uuid()::text,'MCC','8062','Hospitals','{"vertical":"HEALTH","risk":"LOW"}',10,true),
(gen_random_uuid()::text,'MCC','8211','Schools','{"vertical":"EDUCATION","risk":"LOW"}',11,true),
(gen_random_uuid()::text,'MCC','8661','Religious organizations','{"vertical":"NON_PROFIT","risk":"LOW"}',12,true),
(gen_random_uuid()::text,'MCC','5065','Distributors of electronic parts','{"vertical":"WHOLESALE_DISTRIBUTION","risk":"LOW"}',13,true),
(gen_random_uuid()::text,'MCC','5199','Nondurable goods (wholesale)','{"vertical":"WHOLESALE_DISTRIBUTION","risk":"MEDIUM"}',14,true),
(gen_random_uuid()::text,'MCC','4829','Money transfer','{"vertical":"PROFESSIONAL_SERVICES","risk":"HIGH"}',15,true),
(gen_random_uuid()::text,'MCC','6012','Financial institutions','{"vertical":"PROFESSIONAL_SERVICES","risk":"HIGH"}',16,true),
(gen_random_uuid()::text,'MCC','7995','Betting / casinos','{"vertical":"OTHER","risk":"HIGH"}',17,true),
(gen_random_uuid()::text,'MCC','5968','Direct marketers - outbound telesales','{"vertical":"OTHER","risk":"MEDIUM"}',18,true)
on conflict (list_code, value) do nothing;

-- ---------------------------------------------------------------- schema + sections
insert into kyc_schema_versions (id, code, version, status, published_at)
values (gen_random_uuid()::text, 'NA-PSP-BUSINESS', 1, 'PUBLISHED', now());

insert into kyc_sections (id, schema_version_id, key, title, step, kind, min_items, max_items, sort)
select gen_random_uuid()::text, sv.id, t.key, t.title, t.step, t.kind, t.min_items, t.max_items, t.sort
from kyc_schema_versions sv, (values
    ('businessProfile',    'Business',            1, 'OBJECT', null, null, 10),
    ('operationalDetails', 'Operations',          2, 'OBJECT', null, null, 20),
    ('addresses',          'Addresses',           3, 'LIST',   1, 5,   30),
    ('ubos',               'Owners and controllers', 4, 'LIST', 1, 20,  40),
    ('bankAccounts',       'Bank account',        5, 'LIST',   1, 3,   50),
    ('review',             'Review and submit',   6, 'OBJECT', null, null, 60),
    ('documents',          'Documents',           7, 'DOCUMENTS', 0, 0, 70)
) as t(key, title, step, kind, min_items, max_items, sort)
where sv.code = 'NA-PSP-BUSINESS' and sv.version = 1;

-- ---------------------------------------------------------------- fields
-- shape: (section,key,label,help,sort,type,list,req,req_when,vis_when,pattern,normalize,
--         max_len,min_len,min_val,max_val,date_rule,derived,tag,sens,mask)
insert into kyc_fields (id, section_id, key, label, help_text, sort, data_type, list_code,
                        required, required_when, visible_when, pattern, normalize, max_len, min_len,
                        min_value, max_value, date_rule, derived_from, canonical_tag, sensitivity, mask)
select gen_random_uuid()::text, s.id, f.f_key, f.f_label, f.f_help, f.f_sort, f.f_type, f.f_list,
       f.f_req, f.f_req_when::jsonb, f.f_vis_when::jsonb, f.f_pattern, f.f_normalize, f.f_max_len, f.f_min_len,
       f.f_min_val, f.f_max_val, f.f_date_rule, f.f_derived, f.f_tag, f.f_sens, f.f_mask
from kyc_sections s
join (values
  -- businessProfile
  ('businessProfile','entityType','Entity type','Firms must register at BIPA; the entity type drives the rest of the form.',10,'ENUM','NA_ENTITY_TYPE','ALWAYS',null,null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('businessProfile','legalBusinessName','Legal business name','Exactly as on the BIPA registration certificate.',20,'TEXT',null,'ALWAYS',null,null,null,null,200,2,null,null,null,null,'LEGAL_NAME','NONE',null),
  ('businessProfile','tradingName','Trading name (if any)',null,30,'TEXT',null,'NEVER',null,null,null,null,200,null,null,null,null,null,null,'NONE',null),
  ('businessProfile','registrationNumber','BIPA / CC registration number','Stored as typed, e.g. CC/2020/06396 or 2017/1055.',40,'TEXT',null,'WHEN','{"not":{"field":"businessProfile.entityType","eq":"SOLE_PROPRIETOR"}}',null,'^([A-Z]{2})?\d{3,10}$','PUNCT',32,3,null,null,null,null,'REG_NUMBER','NONE',null),
  ('businessProfile','incorporationDate','Date of incorporation','Unless sole proprietor.',50,'DATE',null,'WHEN','{"not":{"field":"businessProfile.entityType","eq":"SOLE_PROPRIETOR"}}',null,null,null,null,null,null,null,'PAST',null,null,'NONE',null),
  ('businessProfile','taxIdentificationNumber','NamRA tax identification number (TIN)','8 digits; a 7-digit legacy reference is stored with a leading 0.',60,'TEXT',null,'ALWAYS',null,null,'^\d{8}$',null,8,7,null,null,null,null,'TAX_ID','SECRET','LAST4'),
  ('businessProfile','vatNumber','VAT registration number (if any)','VAT registration is compulsory from N$1,000,000 in taxable supplies a year.',70,'TEXT',null,'NEVER',null,null,null,null,32,null,null,null,null,null,null,'NONE',null),
  ('businessProfile','countryOfRegistration','Country of registration',null,80,'COUNTRY','COUNTRY','ALWAYS',null,null,null,null,2,null,null,null,null,null,null,'NONE',null),
  -- operationalDetails (fields depend on org type)
  ('operationalDetails','mcc','Merchant category code (MCC)','ISO 18245 code describing the main trade. Searchable.',10,'ENUM','MCC','WHEN','{"context":"orgType","in":["MERCHANT"]}',null,null,null,4,4,null,null,null,null,'MCC','NONE',null),
  ('operationalDetails','industryVertical','Industry vertical','Derived from the MCC where one is shown; otherwise choose.',20,'ENUM','INDUSTRY_VERTICAL','WHEN','{"any":[{"context":"orgType","in":["MERCHANT","SUPPLIER"]},{"not":{"field":"operationalDetails.mcc","exists":true}}]}',null,null,null,null,null,null,null,null,'mcc',null,'NONE',null),
  ('operationalDetails','tradingRole','Trading role',null,30,'ENUM','TRADING_ROLE','WHEN','{"context":"orgType","in":["MANUFACTURER","DISTRIBUTOR","SUPPLIER"]}',null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('operationalDetails','expectedMonthlyVolume','Expected monthly volume band','Internal risk tiering only; there is no legal threshold.',40,'ENUM','NA_VOLUME_BAND','WHEN','{"context":"orgType","in":["MERCHANT","SUPPLIER"]}',null,null,null,null,null,null,null,null,null,'EXPECTED_MONTHLY_VOLUME','NONE',null),
  ('operationalDetails','averageTicketSize','Average transaction size (N$)',null,50,'MONEY',null,'WHEN','{"context":"orgType","in":["MERCHANT"]}',null,null,null,14,0,0,null,null,null,null,'NONE',null),
  ('operationalDetails','annualTurnover','Annual turnover band',null,60,'ENUM','TURNOVER_BAND','WHEN','{"context":"orgType","in":["MANUFACTURER","DISTRIBUTOR"]}',null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('operationalDetails','websiteUrl','Website URL (if any)','https only.',70,'URL',null,'NEVER',null,null,null,null,300,null,null,null,null,null,null,'NONE',null),
  ('operationalDetails','mobileAppLink','Mobile app link (if any)','https only.',80,'URL',null,'NEVER',null,null,null,null,300,null,null,null,null,null,null,'NONE',null),
  ('operationalDetails','storefrontDetails','Storefront details','Where customers find the business. Shown for merchants.',90,'TEXT',null,'WHEN','{"context":"orgType","in":["MERCHANT"]}',null,null,null,500,null,null,null,null,null,null,'NONE',null),
  -- addresses
  ('addresses','addressType','Address type','Exactly one registered address.',10,'ENUM','ADDRESS_TYPE','ALWAYS',null,null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('addresses','sameAsRegistered','Same as registered address','Shown for non-registered addresses; copies the fields on save.',20,'BOOLEAN',null,'NEVER',null,'{"not":{"field":"item.addressType","eq":"REGISTERED"}}',null,null,null,null,null,null,null,null,null,'NONE',null),
  ('addresses','streetLine','Street address',null,30,'TEXT',null,'ALWAYS',null,null,null,null,200,null,null,null,null,null,null,'PII',null),
  ('addresses','town','City / town',null,40,'TEXT',null,'ALWAYS',null,null,null,null,100,null,null,null,null,null,null,'NONE',null),
  ('addresses','region','Region','Required for Namibian addresses.',50,'ENUM','NA_REGION','WHEN','{"field":"item.country","eq":"NA"}',null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('addresses','postalCode','Postal code','Optional in Namibia.',60,'TEXT',null,'NEVER',null,null,null,null,10,null,null,null,null,null,null,'NONE',null),
  ('addresses','country','Country',null,70,'COUNTRY','COUNTRY','ALWAYS',null,null,null,null,2,null,null,null,null,null,null,'NONE',null),
  -- ubos
  ('ubos','fullLegalName','Full legal name','As on the identity document.',10,'TEXT',null,'ALWAYS',null,null,null,null,200,2,null,null,null,null,'UBO_NAME','PII',null),
  ('ubos','roles','Roles','All roles this person holds in the entity.',20,'MULTI_ENUM','UBO_ROLE','ALWAYS',null,null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('ubos','dateOfBirth','Date of birth',null,30,'DATE',null,'ALWAYS',null,null,null,null,null,null,null,null,'PAST',null,'UBO_DOB','PII','HIDE'),
  ('ubos','nationality','Nationality',null,40,'COUNTRY','COUNTRY','ALWAYS',null,null,null,null,2,null,null,null,null,null,'UBO_NATIONALITY','NONE',null),
  ('ubos','identificationType','Identity document type','National ID only for Namibian nationals.',50,'ENUM','ID_TYPE','ALWAYS',null,null,null,null,null,null,null,null,null,null,'UBO_ID_TYPE','NONE',null),
  ('ubos','identificationNumber','Identity number / document number','NA national ID: 11 digits matching the date of birth.',60,'TEXT',null,'ALWAYS',null,null,'^\d{11}$',null,32,5,null,null,null,null,'UBO_ID_NUMBER','SECRET','LAST4'),
  ('ubos','identificationExpiryDate','Identity document expiry date','Not for NA national IDs.',70,'DATE',null,'WHEN','{"not":{"field":"item.identificationType","eq":"NATIONAL_ID"}}',null,null,null,null,null,null,null,'FUTURE',null,'UBO_ID_EXPIRY','NONE',null),
  ('ubos','ownershipType','Ownership type','Direct or indirect. Required with ordinary ownership.',80,'ENUM','OWNERSHIP_TYPE','WHEN','{"any":[{"field":"item.roles","contains":"SHAREHOLDER"},{"field":"item.roles","contains":"MEMBER"},{"field":"item.roles","contains":"PARTNER"},{"field":"item.roles","contains":"PROPRIETOR"}]}',null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('ubos','ownershipPercentage','Ownership percentage','Percent of shares or members interest. Considered against voting rights for the 25% test.',90,'PERCENT',null,'WHEN','{"any":[{"field":"item.roles","contains":"SHAREHOLDER"},{"field":"item.roles","contains":"MEMBER"},{"field":"item.roles","contains":"PARTNER"},{"field":"item.roles","contains":"PROPRIETOR"}]}',null,null,null,6,null,0,100,null,null,'UBO_OWNERSHIP','NONE',null),
  ('ubos','effectiveOwnershipPercentage','Effective ownership percentage','For indirect holdings: the stake multiplied down the ownership chain; used for the 25% test.',100,'PERCENT',null,'WHEN','{"field":"item.ownershipType","eq":"INDIRECT"}',null,null,null,6,0,100,null,null,null,null,'NONE',null),
  ('ubos','votingRightsPercentage','Voting rights percentage','The 25% test considers ownership or voting rights.',110,'PERCENT',null,'NEVER',null,null,null,null,6,0,100,null,null,null,null,'NONE',null),
  ('ubos','controlBasis','Basis of control','FIA Schedule 5: required for controlling persons and senior managers.',120,'ENUM','CONTROL_BASIS','WHEN','{"any":[{"field":"item.roles","contains":"CONTROLLING_PERSON"},{"field":"item.roles","contains":"SENIOR_MANAGER"}]}',null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('ubos','politicallyExposedPerson','Prominent influential person (PIP)',null,130,'BOOLEAN',null,'ALWAYS',null,null,null,null,null,null,null,null,null,null,'UBO_PEP','NONE',null),
  ('ubos','pipCategory','PIP category','FIA Schedule 6 category; required for a PIP.',140,'ENUM','PIP_CATEGORY','WHEN','{"field":"item.politicallyExposedPerson","eq":true}',null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('ubos','pipPosition','PIP position','The office the person holds.',150,'TEXT',null,'WHEN','{"field":"item.politicallyExposedPerson","eq":true}',null,null,null,200,null,null,null,null,null,null,'NONE',null),
  -- bankAccounts
  ('bankAccounts','beneficiaryName','Account holder','Exactly as on the bank confirmation letter.',10,'TEXT',null,'ALWAYS',null,null,null,null,200,2,null,null,null,null,'BANK_BENEFICIARY','NONE',null),
  ('bankAccounts','bankCode','Bank',null,20,'ENUM','NA_BANK','ALWAYS',null,null,null,null,null,null,null,null,null,null,'BANK_CODE','NONE',null),
  ('bankAccounts','branch','Branch','Branch or universal code; the list depends on the selected bank.',30,'ENUM','NA_BANK_BRANCH','WHEN','{"not":{"field":"item.bankCode","eq":"FOREIGN"}}',null,null,null,null,null,null,null,null,'bankCode','BANK_BRANCH','NONE',null),
  ('bankAccounts','accountNumber','Account number','Namibia does not use IBAN.',40,'TEXT',null,'WHEN','{"not":{"field":"item.bankCode","eq":"FOREIGN"}}',null,'^\d{6,16}$',null,16,6,null,null,null,null,'BANK_ACCOUNT_NUMBER','SECRET','LAST4'),
  ('bankAccounts','accountType','Account type',null,50,'ENUM','NA_ACCOUNT_TYPE','ALWAYS',null,null,null,null,null,null,null,null,null,null,null,'NONE',null),
  ('bankAccounts','currency','Account currency','Namibian dollar unless the account is foreign.',60,'ENUM','CURRENCY','ALWAYS',null,'{"not":{"field":"item.bankCode","eq":"FOREIGN"}}',null,null,3,null,null,null,null,null,null,'NONE',null),
  ('bankAccounts','iban','Foreign account IBAN','Shown for foreign bank accounts.',70,'TEXT',null,'WHEN','{"field":"item.bankCode","eq":"FOREIGN"}',null,null,null,34,null,null,null,null,null,null,'NONE',null),
  ('bankAccounts','swiftBic','Foreign bank SWIFT-BIC','Shown for foreign bank accounts.',80,'TEXT',null,'WHEN','{"field":"item.bankCode","eq":"FOREIGN"}',null,'^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$',null,11,8,null,null,null,null,null,'NONE',null),
  ('bankAccounts','foreignBankName','Foreign bank name','Shown for foreign bank accounts.',90,'TEXT',null,'WHEN','{"field":"item.bankCode","eq":"FOREIGN"}',null,null,null,200,null,null,null,null,null,null,'NONE',null)
) as f(section, f_key, f_label, f_help, f_sort, f_type, f_list, f_req, f_req_when, f_vis_when,
       f_pattern, f_normalize, f_max_len, f_min_len, f_min_val, f_max_val, f_date_rule, f_derived, f_tag, f_sens, f_mask)
 on s.schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 1)
and s.key = f.section;

-- branch lists depend on the selected bank (§5.2): the parent field drives the UI filter
-- branch lists depend on the selected bank (§5.2): the parent field drives the UI filter
update kyc_fields f set list_parent_field = 'bankCode'
from kyc_sections s
where f.section_id = s.id and s.key = 'bankAccounts'
  and s.schema_version_id = (select id from kyc_schema_versions where code = 'NA-PSP-BUSINESS' and version = 1)
  and f.key = 'branch';

-- ---------------------------------------------------------------- rules
-- shape: (code, phase, validator_key, params, message)
insert into kyc_rules (id, schema_version_id, code, phase, validator_key, params, message)
select gen_random_uuid()::text, sv.id, t.code, t.phase, t.validator_key, t.params::jsonb, t.message
from kyc_schema_versions sv, (values
    ('REG_ADDRESS_EXACT_ONE','SUBMIT','LIST_COUNT',
     '{"section":"addresses","field":"addressType","value":["REGISTERED"],"min":1,"max":1}',
     'Exactly one registered address is required.'),
    ('OWNERSHIP_SUM_MAX','SUBMIT','SUM_MAX',
     '{"section":"ubos","field":"ownershipPercentage","max":100}',
     'The combined ownership percentages cannot exceed 100%.'),
    ('UBO_THRESHOLD','SUBMIT','ANY_GTE',
     '{"section":"ubos","fields":["ownershipPercentage","effectiveOwnershipPercentage","votingRightsPercentage"],"thresholdPct":"@uboThresholdPct","controlBasis":"controlBasis"}',
     'Add a beneficial owner with at least 25% ownership or voting rights, or declare the control basis (FIA Schedule 5).'),
    ('SOLE_PROPRIETOR_SINGLE_OWNER','SUBMIT','LIST_COUNT',
     '{"section":"ubos","field":"roles","value":["PROPRIETOR"],"min":1,"max":1,"whenContext":{"field":"businessProfile.entityType","eq":"SOLE_PROPRIETOR"}}',
     'A sole proprietor is the single owner of the business.'),
    ('SOLE_PROPRIETOR_HUNDRED_PCT','SUBMIT','VALUE_EQ',
     '{"section":"ubos","field":"ownershipPercentage","eq":100,"whenContext":{"field":"businessProfile.entityType","eq":"SOLE_PROPRIETOR"}}',
     'A sole proprietor holds 100% of the business.'),
    ('TRUST_TRUSTEE','SUBMIT','ROLE_PRESENT',
     '{"section":"ubos","field":"roles","values":["TRUSTEE"],"whenContext":{"field":"businessProfile.entityType","eq":"TRUST"}}',
     'Trusts must name the settlor, every trustee, any protector and the beneficiaries; at least one trustee is required.'),
    ('OWNER_MIN_AGE','SUBMIT','MIN_AGE',
     '{"section":"ubos","field":"dateOfBirth","minAge":"@minOwnerAge"}',
     'Every named owner must be at least the minimum age.'),
    ('NA_ID_MATCHES_DOB','SUBMIT','ID_MATCHES_DOB',
     '{"section":"ubos","idField":"identificationNumber","dobField":"dateOfBirth","typeField":"identificationType","idType":"NATIONAL_ID"}',
     'A NA national ID must match the date of birth (first six digits = YYMMDD).'),
    ('BENEFICIARY_NAME_MATCH','SUBMIT','NAME_MATCH',
     '{"section":"bankAccounts","nameField":"beneficiaryName","against":["businessProfile.legalBusinessName","businessProfile.tradingName"]}',
     'The account holder should match the legal or trading name (warning).'),
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
where sv.code = 'NA-PSP-BUSINESS' and sv.version = 1;

-- ---------------------------------------------------------------- profiles (one per org type)
insert into kyc_profiles (id, code, country, licence_type, subject_type, entity_type, org_type,
                          schema_version_id, workflow_key, workflow_version, workflow_config,
                          collection_provider, check_providers, risk_scorer, params, ui_enabled, active)
select gen_random_uuid()::text, 'NA-PSP-BUSINESS-' || t.org_type, 'NA', 'PSP', 'BUSINESS', null, t.org_type,
       sv.id, 'configurable', 1,
       '{"approvalRequired":true,"entryCheck":"OPTIONAL","verification":["IDENTITY","SANCTIONS","PEP"],
         "stages":[{"key":"ops-review","actor":{"relation":"PLATFORM","function":"OPS_REVIEWER"},
                    "outcomes":["APPROVE","RETURN","REJECT","BLOCK"]}],
         "contributors":["high-risk-or-pep"]}'::jsonb,
       'native-form',
       '{"IDENTITY":"simulated","SANCTIONS":"simulated","PEP":"simulated"}'::jsonb,
       'default',
       '{"uboThresholdPct":25,"minOwnerAge":18,
         "reviewMonths":{"LOW":36,"MEDIUM":24,"HIGH":12},"checkTimeoutHours":24}'::jsonb,
       true, true
from (values ('MERCHANT'),('SUPPLIER'),('MANUFACTURER'),('DISTRIBUTOR'),('LENDER')) as t(org_type),
     kyc_schema_versions sv
where sv.code = 'NA-PSP-BUSINESS' and sv.version = 1;
