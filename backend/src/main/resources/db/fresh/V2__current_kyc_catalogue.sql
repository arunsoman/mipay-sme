--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: kyc_schema_versions; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.kyc_schema_versions VALUES ('7e44bd0c-1726-4487-861e-a560338c58c3', 'NA-PSP-BUSINESS', 3, 'PUBLISHED', '2026-11-14 02:13:25.790689+00');


--
-- Data for Name: kyc_sections; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.kyc_sections VALUES ('a0b607fd-18fb-4234-a59f-ca7959f6437b', '7e44bd0c-1726-4487-861e-a560338c58c3', 'businessProfile', 'Business Profile & General Information', 1, 'OBJECT', NULL, NULL, NULL, 10);
INSERT INTO public.kyc_sections VALUES ('64ffe059-8331-41bc-bcc5-52e433451f2d', '7e44bd0c-1726-4487-861e-a560338c58c3', 'operationalDetails', 'Operational & Store Details', 2, 'OBJECT', NULL, NULL, NULL, 20);
INSERT INTO public.kyc_sections VALUES ('9024eecf-bcbf-4df7-9e92-c0c6f8a9be9c', '7e44bd0c-1726-4487-861e-a560338c58c3', 'addresses', 'Registered & Operational Address', 3, 'LIST', 1, 2, NULL, 30);
INSERT INTO public.kyc_sections VALUES ('4009006e-a76c-4a90-abc5-a1da01477975', '7e44bd0c-1726-4487-861e-a560338c58c3', 'ubos', 'Ultimate Beneficial Owners (UBO) & Key Management', 4, 'LIST', 1, 20, NULL, 40);
INSERT INTO public.kyc_sections VALUES ('d5f61b85-cce6-4121-8646-74d2ff755fa7', '7e44bd0c-1726-4487-861e-a560338c58c3', 'bankAccounts', 'Settlement & Payout Bank Account Details', 5, 'LIST', 1, 1, NULL, 50);


--
-- Data for Name: kyc_fields; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.kyc_fields VALUES ('d7f10f3e-fbeb-4dd8-bcc6-75450d3bdcf9', 'a0b607fd-18fb-4234-a59f-ca7959f6437b', 'legalBusinessName', 'Legal Business Name', 'Exact registered name as listed in legal government tax registries.', NULL, 10, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 2, 200, NULL, NULL, NULL, NULL, 'LEGAL_NAME', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('cb959871-ef5a-4be5-9255-2872be5a9dbc', 'a0b607fd-18fb-4234-a59f-ca7959f6437b', 'tradingName', 'Doing Business As (DBA) / Trading Name', 'Public-facing store or brand name displayed to customers on checkout pages and bank statements.', NULL, 20, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 1, 200, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('88ebd5a4-d831-4185-bdea-77405963a059', 'a0b607fd-18fb-4234-a59f-ca7959f6437b', 'entityType', 'Entity Type / Legal Structure', 'Sole Proprietorship, Partnership, Private Limited / LLC, Public Corporation, or Trust / NGO.', NULL, 30, 'ENUM', 'KYC_ENTITY_TYPE', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('18f5f14f-e747-4794-8819-c50721e4b83a', 'a0b607fd-18fb-4234-a59f-ca7959f6437b', 'registrationNumber', 'Business Registration / Corporate ID Number', 'Official registration number (e.g. CIN, Company Registration No.).', NULL, 40, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, 'PUNCT', 3, 32, NULL, NULL, NULL, NULL, 'REG_NUMBER', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('b30d96df-b747-4105-88f8-dc19c074692d', 'a0b607fd-18fb-4234-a59f-ca7959f6437b', 'incorporationDate', 'Date of Incorporation / Establishment', 'Date the business was legally formed (DD/MM/YYYY).', NULL, 50, 'DATE', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'PAST', NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('b3bc925e-e4a6-4f97-ab32-79ebd22c071b', 'a0b607fd-18fb-4234-a59f-ca7959f6437b', 'taxIdentificationNumber', 'Tax Identification Number (TIN / EIN / GSTIN / VAT ID)', 'Primary tax registration identifier.', NULL, 60, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 5, 32, NULL, NULL, NULL, NULL, 'TAX_ID', 'SECRET', 'LAST4');
INSERT INTO public.kyc_fields VALUES ('f1751e85-b10a-4923-b64e-b194a43d1680', '64ffe059-8331-41bc-bcc5-52e433451f2d', 'mcc', 'Merchant Category Code (MCC) / Industry Vertical', '4-digit classification code (e.g. Retail, SaaS, Travel, F&B).', NULL, 10, 'ENUM', 'MCC', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 4, 4, NULL, NULL, NULL, NULL, 'MCC', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('ca2be0e6-75b8-4fe2-85ee-f2593f939b09', '64ffe059-8331-41bc-bcc5-52e433451f2d', 'websiteOrStorefront', 'Website URL / Mobile App Link / Storefront Details', 'Digital footprint or physical store location used for products and terms verification.', NULL, 20, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 3, 500, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('294c08bf-e8d4-4487-9d42-29f58dc9f8be', '64ffe059-8331-41bc-bcc5-52e433451f2d', 'expectedMonthlyVolume', 'Expected Monthly Processing Volume', 'Estimated monetary volume used to configure initial risk limits and pricing tiers.', NULL, 30, 'ENUM', 'NA_VOLUME_BAND', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'EXPECTED_MONTHLY_VOLUME', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('990d2021-d814-40d3-8d5e-b16f6fdeae49', '64ffe059-8331-41bc-bcc5-52e433451f2d', 'averageTicketSize', 'Average Ticket Size / Transaction Value', 'Typical value of a single consumer transaction.', NULL, 40, 'MONEY', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, 14, 0.0000000000, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('8f348bc6-39db-47cc-8a34-6455d184dfde', '9024eecf-bcbf-4df7-9e92-c0c6f8a9be9c', 'addressType', 'Address', 'Registered office address (matching official registration documents); add an operational / store address only if it is different.', NULL, 10, 'ENUM', 'KYC_ADDRESS_TYPE', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('aebc35ae-9caf-4304-bf44-7ede02905093', '9024eecf-bcbf-4df7-9e92-c0c6f8a9be9c', 'streetLine', 'Street Line', NULL, NULL, 20, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 2, 200, NULL, NULL, NULL, NULL, NULL, 'PII', NULL);
INSERT INTO public.kyc_fields VALUES ('84d45f58-ab9d-4e0e-8247-5957ad3541f7', '9024eecf-bcbf-4df7-9e92-c0c6f8a9be9c', 'town', 'City', NULL, NULL, 30, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 2, 100, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('1548dc5d-8357-4631-b699-a485240146ed', '9024eecf-bcbf-4df7-9e92-c0c6f8a9be9c', 'region', 'State / Province', NULL, NULL, 40, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 2, 100, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('b5f0895b-d9b3-460a-ae24-1f1e4a7b28ee', '9024eecf-bcbf-4df7-9e92-c0c6f8a9be9c', 'postalCode', 'Postal Code', NULL, NULL, 50, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 2, 10, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('04f6ffcb-a7cf-4709-a0e9-2ee9759c60b3', '9024eecf-bcbf-4df7-9e92-c0c6f8a9be9c', 'country', 'Country', NULL, NULL, 60, 'COUNTRY', 'COUNTRY', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, 2, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('0a550aa7-7313-4141-ac0e-61644bb928d0', '4009006e-a76c-4a90-abc5-a1da01477975', 'fullLegalName', 'Full Legal Name', 'Complete legal name of Directors, Partners, or UBOs.', NULL, 10, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 2, 200, NULL, NULL, NULL, NULL, 'UBO_NAME', 'PII', NULL);
INSERT INTO public.kyc_fields VALUES ('fc4feba8-90ed-4d8d-b634-1c01400bf715', '4009006e-a76c-4a90-abc5-a1da01477975', 'dateOfBirth', 'Date of Birth (DOB)', NULL, NULL, 20, 'DATE', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'PAST', NULL, 'UBO_DOB', 'PII', 'HIDE');
INSERT INTO public.kyc_fields VALUES ('4dcfb973-91ef-487e-bd8a-c07d0cc30ce3', '4009006e-a76c-4a90-abc5-a1da01477975', 'nationality', 'Nationality', NULL, NULL, 30, 'COUNTRY', 'COUNTRY', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, 2, NULL, NULL, NULL, NULL, 'UBO_NATIONALITY', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('9faf9092-c95e-4ec5-bc65-361ff11a015d', '4009006e-a76c-4a90-abc5-a1da01477975', 'residentialAddress', 'Residential Address', 'Current home address of key personnel.', NULL, 40, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 5, 300, NULL, NULL, NULL, NULL, 'UBO_ADDRESS', 'PII', 'HIDE');
INSERT INTO public.kyc_fields VALUES ('bcbabd02-0903-4465-ad29-6a024f7cc3aa', '4009006e-a76c-4a90-abc5-a1da01477975', 'identificationType', 'Government-Issued ID: Document Type', 'Passport, National ID, or Driver''s License.', NULL, 50, 'ENUM', 'KYC_ID_DOC_TYPE', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'UBO_ID_TYPE', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('e7234c17-5fe3-44d0-a5d1-d131ab21410d', '4009006e-a76c-4a90-abc5-a1da01477975', 'identificationNumber', 'Government-Issued ID: ID Number', NULL, NULL, 60, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 5, 32, NULL, NULL, NULL, NULL, 'UBO_ID_NUMBER', 'SECRET', 'LAST4');
INSERT INTO public.kyc_fields VALUES ('0a360f21-9763-46f4-8d9c-fd24e90276af', '4009006e-a76c-4a90-abc5-a1da01477975', 'identificationExpiryDate', 'Government-Issued ID: Expiry Date', 'Leave empty only for a national ID without an expiry date.', NULL, 70, 'DATE', NULL, NULL, 'WHEN', '{"not": {"eq": "NATIONAL_ID", "field": "item.identificationType"}}', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'FUTURE', NULL, 'UBO_ID_EXPIRY', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('d62d3855-40e6-438f-9401-e9cae77a667b', '4009006e-a76c-4a90-abc5-a1da01477975', 'ownershipPercentage', 'Ownership Percentage', 'Shareholding or voting rights distribution (≥ 10% to 25% threshold). Enter 0 for directors without a share.', NULL, 80, 'PERCENT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, 6, 0.0000000000, 100.0000000000, NULL, NULL, 'UBO_OWNERSHIP', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('5678c576-30a7-4d44-b3d7-f17bf65bcb19', '4009006e-a76c-4a90-abc5-a1da01477975', 'politicallyExposedPerson', 'Politically Exposed Person (PEP) Declaration', 'Does the individual or a close associate hold public office?', NULL, 90, 'BOOLEAN', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'UBO_PEP', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('972db6bc-c458-4835-adb7-30d5b429ac10', 'd5f61b85-cce6-4121-8646-74d2ff755fa7', 'beneficiaryName', 'Beneficiary / Account Holder Name', 'Must strictly match the Legal Business Name or the Sole Proprietor''s name.', NULL, 10, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 2, 200, NULL, NULL, NULL, NULL, 'BANK_BENEFICIARY', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('e81b343a-b520-4f32-a7d5-1027975eb2e9', 'd5f61b85-cce6-4121-8646-74d2ff755fa7', 'bankCode', 'Bank Name', NULL, NULL, 20, 'ENUM', 'NA_BANK', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'BANK_CODE', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('1a62c527-d76f-43e6-900d-8b4076aac206', 'd5f61b85-cce6-4121-8646-74d2ff755fa7', 'institutionCode', 'Branch / Institution Code', 'Institution identifier (e.g. IFSC, Sort Code, Routing Number, SWIFT).', NULL, 30, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 3, 34, NULL, NULL, NULL, NULL, 'BANK_BRANCH', 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('ab9da088-9100-463e-8bce-1701e2c8a7c0', 'd5f61b85-cce6-4121-8646-74d2ff755fa7', 'accountNumber', 'Account Number / IBAN', 'Destination account for settlement payouts.', NULL, 40, 'TEXT', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 6, 34, NULL, NULL, NULL, NULL, 'BANK_ACCOUNT_NUMBER', 'SECRET', 'LAST4');
INSERT INTO public.kyc_fields VALUES ('d0588389-dfb2-4d0a-8fec-a0e4d2af4722', 'd5f61b85-cce6-4121-8646-74d2ff755fa7', 'accountType', 'Account Type', 'Checking / Current vs. Savings.', NULL, 50, 'ENUM', 'KYC_ACCOUNT_TYPE', NULL, 'ALWAYS', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'NONE', NULL);
INSERT INTO public.kyc_fields VALUES ('a99ad801-1178-4097-8f32-96d5f400d117', '4009006e-a76c-4a90-abc5-a1da01477975', 'phoneNumber', 'Phone Number', 'Mobile number. For Person 1 it is also the MiPay administrator''s login contact.', NULL, 45, 'PHONE', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 6, 20, NULL, NULL, NULL, NULL, 'UBO_PHONE', 'PII', NULL);
INSERT INTO public.kyc_fields VALUES ('49ccd9c1-b87c-47a5-8807-fc58a95ebd3f', '4009006e-a76c-4a90-abc5-a1da01477975', 'emailAddress', 'Email Address', 'For Person 1, the MiPay username and activation link are sent here.', NULL, 46, 'EMAIL', NULL, NULL, 'ALWAYS', NULL, NULL, NULL, NULL, 5, 200, NULL, NULL, NULL, NULL, 'UBO_EMAIL', 'PII', NULL);


--
-- Data for Name: kyc_lists; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.kyc_lists VALUES ('COUNTRY', NULL, 'ISO 3166-1 alpha-2 selectable countries');
INSERT INTO public.kyc_lists VALUES ('NA_BANK', 'NA', 'Authorised Namibian banking institutions');
INSERT INTO public.kyc_lists VALUES ('MCC', NULL, 'ISO 18245 merchant category codes (subset)');
INSERT INTO public.kyc_lists VALUES ('NA_VOLUME_BAND', 'NA', 'Expected monthly volume band (N$/month; internal risk tier, FIA s.20A)');
INSERT INTO public.kyc_lists VALUES ('KYC_ENTITY_TYPE', NULL, 'Entity type / legal structure (merchant data capture spec)');
INSERT INTO public.kyc_lists VALUES ('KYC_ADDRESS_TYPE', NULL, 'Registered office or operational / store address');
INSERT INTO public.kyc_lists VALUES ('KYC_ID_DOC_TYPE', NULL, 'Government-issued identification document type');
INSERT INTO public.kyc_lists VALUES ('KYC_ACCOUNT_TYPE', NULL, 'Settlement account type');


--
-- Data for Name: kyc_list_items; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.kyc_list_items VALUES ('504d15be-3dc1-4d39-a80f-e7c2baf45091', 'COUNTRY', 'NA', 'Namibia', NULL, NULL, 1, true);
INSERT INTO public.kyc_list_items VALUES ('0bb28619-9344-42bc-9f96-645e8428a6f4', 'COUNTRY', 'ZA', 'South Africa', NULL, NULL, 2, true);
INSERT INTO public.kyc_list_items VALUES ('6d443605-8ff7-4fb5-8d5b-d250aa0bc937', 'COUNTRY', 'BW', 'Botswana', NULL, NULL, 3, true);
INSERT INTO public.kyc_list_items VALUES ('fe4cafac-4f3c-4d58-9b84-4ba5c469f79a', 'COUNTRY', 'SZ', 'Eswatini', NULL, NULL, 4, true);
INSERT INTO public.kyc_list_items VALUES ('00aef6cd-85ee-4a5b-88b4-02ccaaa22b6f', 'COUNTRY', 'LS', 'Lesotho', NULL, NULL, 5, true);
INSERT INTO public.kyc_list_items VALUES ('4f69905c-cc85-482c-a2ba-017a5cfcd660', 'COUNTRY', 'ZM', 'Zambia', NULL, NULL, 6, true);
INSERT INTO public.kyc_list_items VALUES ('44bbe2f1-05e8-4ed4-ae97-614a14e7401f', 'COUNTRY', 'ZW', 'Zimbabwe', NULL, NULL, 7, true);
INSERT INTO public.kyc_list_items VALUES ('aadcbcd4-bc7d-470d-b5cf-d03d4485c7e1', 'COUNTRY', 'MZ', 'Mozambique', NULL, NULL, 8, true);
INSERT INTO public.kyc_list_items VALUES ('45966a38-a371-4b98-a1a2-564ef90733ef', 'COUNTRY', 'AO', 'Angola', NULL, NULL, 9, true);
INSERT INTO public.kyc_list_items VALUES ('e1f9ffda-e207-4cbc-86e1-08637e6bafbf', 'COUNTRY', 'OTHER', 'Other', NULL, NULL, 99, true);
INSERT INTO public.kyc_list_items VALUES ('dbff09e0-dafc-436a-a7b1-7225203196fb', 'NA_BANK', 'BANK_WINDHOEK', 'Bank Windhoek', NULL, NULL, 1, true);
INSERT INTO public.kyc_list_items VALUES ('e77daeb1-4d7c-43ca-bc66-1f397abc80f1', 'NA_BANK', 'FNB_NAMIBIA', 'First National Bank Namibia', NULL, NULL, 2, true);
INSERT INTO public.kyc_list_items VALUES ('146f7094-4642-4d2f-b2de-dfad4f86570c', 'NA_BANK', 'STANDARD_BANK_NA', 'Standard Bank Namibia', NULL, NULL, 3, true);
INSERT INTO public.kyc_list_items VALUES ('5f70989e-c836-406b-abbc-621cdce56b96', 'NA_BANK', 'NEDBANK_NA', 'Nedbank Namibia', NULL, NULL, 4, true);
INSERT INTO public.kyc_list_items VALUES ('bf186849-7067-4b5e-9a92-a07303b7d547', 'NA_BANK', 'BANK_BIC', 'Bank BIC Namibia', NULL, NULL, 5, true);
INSERT INTO public.kyc_list_items VALUES ('aa5db25e-625f-4635-acf0-865ecb24ca1e', 'NA_BANK', 'LETSHEGO', 'Letshego Bank Namibia', NULL, NULL, 6, true);
INSERT INTO public.kyc_list_items VALUES ('37e922ae-101f-45f7-a44e-0a3ee74fe882', 'NA_BANK', 'FOREIGN', 'Foreign bank account', NULL, NULL, 99, true);
INSERT INTO public.kyc_list_items VALUES ('f8ea584b-bd66-4daa-81b8-068accbbfe54', 'NA_VOLUME_BAND', 'BELOW_50K', 'Under N$50,000 per month', NULL, NULL, 1, true);
INSERT INTO public.kyc_list_items VALUES ('3ec40e02-f7d9-463b-bfc1-e2970b94a285', 'NA_VOLUME_BAND', '50K_250K', 'N$50,000 to N$250,000 per month', NULL, NULL, 2, true);
INSERT INTO public.kyc_list_items VALUES ('e5fefded-01c8-4a42-917e-501199f5c06e', 'NA_VOLUME_BAND', '250K_1M', 'N$250,000 to N$1 million per month', NULL, NULL, 3, true);
INSERT INTO public.kyc_list_items VALUES ('a99e054a-9516-4afb-abfe-6cd8f60f3e8b', 'NA_VOLUME_BAND', '1M_5M', 'N$1 million to N$5 million per month', NULL, NULL, 4, true);
INSERT INTO public.kyc_list_items VALUES ('ed46a3b5-14da-43d5-a200-4c19137345e5', 'NA_VOLUME_BAND', 'OVER_5M', 'Over N$5 million per month', NULL, NULL, 5, true);
INSERT INTO public.kyc_list_items VALUES ('5fcb18cd-371a-44fa-96a0-6c50d7f7d9a5', 'MCC', '5411', 'Grocery stores, supermarkets', NULL, '{"risk": "LOW", "vertical": "RETAIL"}', 1, true);
INSERT INTO public.kyc_list_items VALUES ('ca54a790-ce15-4c48-a18a-4deda040bcfe', 'MCC', '5812', 'Eating places, restaurants', NULL, '{"risk": "LOW", "vertical": "FOOD_BEVERAGE"}', 2, true);
INSERT INTO public.kyc_list_items VALUES ('91bb90ff-69ae-4e49-bb19-c808ae98fd90', 'MCC', '5813', 'Drinking places', NULL, '{"risk": "MEDIUM", "vertical": "FOOD_BEVERAGE"}', 3, true);
INSERT INTO public.kyc_list_items VALUES ('144244aa-7fd3-4944-8b3b-82b558ad24eb', 'MCC', '5541', 'Service stations', NULL, '{"risk": "LOW", "vertical": "RETAIL"}', 4, true);
INSERT INTO public.kyc_list_items VALUES ('00f81a64-988d-4151-8bc8-53316edd9a6c', 'MCC', '5311', 'Department stores', NULL, '{"risk": "LOW", "vertical": "RETAIL"}', 5, true);
INSERT INTO public.kyc_list_items VALUES ('dfc425bf-baa5-47af-9a0d-029856e2fa60', 'MCC', '5651', 'Clothing stores', NULL, '{"risk": "LOW", "vertical": "RETAIL"}', 6, true);
INSERT INTO public.kyc_list_items VALUES ('9173d9ea-caf5-4aa2-aa6e-64d833834f8a', 'MCC', '5999', 'Specialty retail stores', NULL, '{"risk": "MEDIUM", "vertical": "RETAIL"}', 7, true);
INSERT INTO public.kyc_list_items VALUES ('bca7e5ae-99ae-47b1-9363-0480271c168a', 'MCC', '4121', 'Taxicabs and limousines', NULL, '{"risk": "MEDIUM", "vertical": "TRANSPORT"}', 8, true);
INSERT INTO public.kyc_list_items VALUES ('041919d5-51c1-4c16-9b19-1badac2d9a04', 'MCC', '7011', 'Hotels and lodging', NULL, '{"risk": "LOW", "vertical": "HOSPITALITY"}', 9, true);
INSERT INTO public.kyc_list_items VALUES ('45c28ebc-5c73-41e6-bf03-5ae9242c8585', 'MCC', '8062', 'Hospitals', NULL, '{"risk": "LOW", "vertical": "HEALTH"}', 10, true);
INSERT INTO public.kyc_list_items VALUES ('f3981fdb-a6a7-40ca-b0cd-3585afb8a89e', 'MCC', '8211', 'Schools', NULL, '{"risk": "LOW", "vertical": "EDUCATION"}', 11, true);
INSERT INTO public.kyc_list_items VALUES ('80e6262f-06a3-44d2-bd27-9ead524a3461', 'MCC', '8661', 'Religious organizations', NULL, '{"risk": "LOW", "vertical": "NON_PROFIT"}', 12, true);
INSERT INTO public.kyc_list_items VALUES ('8e0c8f58-fb11-41b3-94a0-bbb4c3fe76b1', 'MCC', '5065', 'Distributors of electronic parts', NULL, '{"risk": "LOW", "vertical": "WHOLESALE_DISTRIBUTION"}', 13, true);
INSERT INTO public.kyc_list_items VALUES ('8c7ed1d1-e23f-4a86-8ac9-5cdac145151b', 'MCC', '5199', 'Nondurable goods (wholesale)', NULL, '{"risk": "MEDIUM", "vertical": "WHOLESALE_DISTRIBUTION"}', 14, true);
INSERT INTO public.kyc_list_items VALUES ('98b3d6e4-6517-485b-9c8c-b530ef9ea0e6', 'MCC', '4829', 'Money transfer', NULL, '{"risk": "HIGH", "vertical": "PROFESSIONAL_SERVICES"}', 15, true);
INSERT INTO public.kyc_list_items VALUES ('f8a0deb4-3e73-4201-b808-9715807156f7', 'MCC', '6012', 'Financial institutions', NULL, '{"risk": "HIGH", "vertical": "PROFESSIONAL_SERVICES"}', 16, true);
INSERT INTO public.kyc_list_items VALUES ('b6f1fc03-023c-402a-a98b-7a08ee1648be', 'MCC', '7995', 'Betting / casinos', NULL, '{"risk": "HIGH", "vertical": "OTHER"}', 17, true);
INSERT INTO public.kyc_list_items VALUES ('7ca514a4-fd22-46a4-9e8c-e46085f49adc', 'MCC', '5968', 'Direct marketers - outbound telesales', NULL, '{"risk": "MEDIUM", "vertical": "OTHER"}', 18, true);
INSERT INTO public.kyc_list_items VALUES ('575a5ce5-d224-4dc6-b436-109924af372d', 'KYC_ENTITY_TYPE', 'SOLE_PROPRIETOR', 'Sole Proprietorship', NULL, NULL, 1, true);
INSERT INTO public.kyc_list_items VALUES ('c462eae0-ec98-4735-bfee-01d75823eea4', 'KYC_ENTITY_TYPE', 'PARTNERSHIP', 'Partnership', NULL, NULL, 2, true);
INSERT INTO public.kyc_list_items VALUES ('b3bedd2f-079a-4192-918a-3b7dbe05da7b', 'KYC_ENTITY_TYPE', 'PRIVATE_LIMITED', 'Private Limited / LLC', NULL, NULL, 3, true);
INSERT INTO public.kyc_list_items VALUES ('bd6d6cf5-0345-42b0-befe-2042b46c3164', 'KYC_ENTITY_TYPE', 'PUBLIC_CORPORATION', 'Public Corporation', NULL, NULL, 4, true);
INSERT INTO public.kyc_list_items VALUES ('63ff5f7b-70fd-4767-9562-c3025c4f3139', 'KYC_ENTITY_TYPE', 'TRUST_NGO', 'Trust / NGO', NULL, NULL, 5, true);
INSERT INTO public.kyc_list_items VALUES ('57b4c104-70f6-4639-a787-835c7b20f94d', 'KYC_ADDRESS_TYPE', 'REGISTERED', 'Registered office address', NULL, NULL, 1, true);
INSERT INTO public.kyc_list_items VALUES ('5e799b8d-b0dd-4a12-9134-ce5b8d1bd727', 'KYC_ADDRESS_TYPE', 'OPERATING', 'Operational / store address', NULL, NULL, 2, true);
INSERT INTO public.kyc_list_items VALUES ('a9563c82-1601-4552-9b84-d22c9d940eb8', 'KYC_ID_DOC_TYPE', 'PASSPORT', 'Passport', NULL, NULL, 1, true);
INSERT INTO public.kyc_list_items VALUES ('f211ed7d-d413-49ee-b4a3-7dbdee351d8a', 'KYC_ID_DOC_TYPE', 'NATIONAL_ID', 'National ID', NULL, NULL, 2, true);
INSERT INTO public.kyc_list_items VALUES ('8bacc0e4-0a72-4bb6-9948-c449f3201e1d', 'KYC_ID_DOC_TYPE', 'DRIVING_LICENCE', 'Driver''s License', NULL, NULL, 3, true);
INSERT INTO public.kyc_list_items VALUES ('bac29ec4-f692-4360-bace-ecc3f7ccde55', 'KYC_ACCOUNT_TYPE', 'CURRENT', 'Checking / Current', NULL, NULL, 1, true);
INSERT INTO public.kyc_list_items VALUES ('283acb8d-3320-41cf-b3f6-b97723505909', 'KYC_ACCOUNT_TYPE', 'SAVINGS', 'Savings', NULL, NULL, 2, true);


--
-- Data for Name: kyc_profiles; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.kyc_profiles VALUES ('1f604f18-10a1-4a62-ba39-f6455b13005e', 'NA-PSP-BUSINESS-MERCHANT', 'NA', 'PSP', 'BUSINESS', NULL, 'MERCHANT', '7e44bd0c-1726-4487-861e-a560338c58c3', 'configurable', 1, '{"stages": [{"key": "ops-review", "actor": {"function": "OPS_REVIEWER", "relation": "PLATFORM"}, "outcomes": ["APPROVE", "RETURN", "REJECT", "BLOCK"]}], "entryCheck": "OPTIONAL", "contributors": ["high-risk-or-pep"], "verification": ["IDENTITY", "SANCTIONS", "PEP"], "approvalRequired": true}', 'native-form', '{"PEP": "simulated", "IDENTITY": "simulated", "SANCTIONS": "simulated"}', 'default', '{"minOwnerAge": 18, "reviewMonths": {"LOW": 36, "HIGH": 12, "MEDIUM": 24}, "uboThresholdPct": 25, "checkTimeoutHours": 24}', true, true, '2026-11-14 02:13:24.21602+00');
INSERT INTO public.kyc_profiles VALUES ('ea070f67-ed9f-4f62-9ac3-c0690078e12a', 'NA-PSP-BUSINESS-SUPPLIER', 'NA', 'PSP', 'BUSINESS', NULL, 'SUPPLIER', '7e44bd0c-1726-4487-861e-a560338c58c3', 'configurable', 1, '{"stages": [{"key": "ops-review", "actor": {"function": "OPS_REVIEWER", "relation": "PLATFORM"}, "outcomes": ["APPROVE", "RETURN", "REJECT", "BLOCK"]}], "entryCheck": "OPTIONAL", "contributors": ["high-risk-or-pep"], "verification": ["IDENTITY", "SANCTIONS", "PEP"], "approvalRequired": true}', 'native-form', '{"PEP": "simulated", "IDENTITY": "simulated", "SANCTIONS": "simulated"}', 'default', '{"minOwnerAge": 18, "reviewMonths": {"LOW": 36, "HIGH": 12, "MEDIUM": 24}, "uboThresholdPct": 25, "checkTimeoutHours": 24}', true, true, '2026-11-14 02:13:24.21602+00');
INSERT INTO public.kyc_profiles VALUES ('5b7727c1-1bd4-4887-b178-59ca09b9d830', 'NA-PSP-BUSINESS-MANUFACTURER', 'NA', 'PSP', 'BUSINESS', NULL, 'MANUFACTURER', '7e44bd0c-1726-4487-861e-a560338c58c3', 'configurable', 1, '{"stages": [{"key": "ops-review", "actor": {"function": "OPS_REVIEWER", "relation": "PLATFORM"}, "outcomes": ["APPROVE", "RETURN", "REJECT", "BLOCK"]}], "entryCheck": "OPTIONAL", "contributors": ["high-risk-or-pep"], "verification": ["IDENTITY", "SANCTIONS", "PEP"], "approvalRequired": true}', 'native-form', '{"PEP": "simulated", "IDENTITY": "simulated", "SANCTIONS": "simulated"}', 'default', '{"minOwnerAge": 18, "reviewMonths": {"LOW": 36, "HIGH": 12, "MEDIUM": 24}, "uboThresholdPct": 25, "checkTimeoutHours": 24}', true, true, '2026-11-14 02:13:24.21602+00');
INSERT INTO public.kyc_profiles VALUES ('fe1f0dbb-6d82-4356-9ac9-b0154d0bc5f3', 'NA-PSP-BUSINESS-DISTRIBUTOR', 'NA', 'PSP', 'BUSINESS', NULL, 'DISTRIBUTOR', '7e44bd0c-1726-4487-861e-a560338c58c3', 'configurable', 1, '{"stages": [{"key": "ops-review", "actor": {"function": "OPS_REVIEWER", "relation": "PLATFORM"}, "outcomes": ["APPROVE", "RETURN", "REJECT", "BLOCK"]}], "entryCheck": "OPTIONAL", "contributors": ["high-risk-or-pep"], "verification": ["IDENTITY", "SANCTIONS", "PEP"], "approvalRequired": true}', 'native-form', '{"PEP": "simulated", "IDENTITY": "simulated", "SANCTIONS": "simulated"}', 'default', '{"minOwnerAge": 18, "reviewMonths": {"LOW": 36, "HIGH": 12, "MEDIUM": 24}, "uboThresholdPct": 25, "checkTimeoutHours": 24}', true, true, '2026-11-14 02:13:24.21602+00');
INSERT INTO public.kyc_profiles VALUES ('9654f5c9-182e-4ffa-89d1-49b7dc4d5753', 'NA-PSP-BUSINESS-LENDER', 'NA', 'PSP', 'BUSINESS', NULL, 'LENDER', '7e44bd0c-1726-4487-861e-a560338c58c3', 'configurable', 1, '{"stages": [{"key": "ops-review", "actor": {"function": "OPS_REVIEWER", "relation": "PLATFORM"}, "outcomes": ["APPROVE", "RETURN", "REJECT", "BLOCK"]}], "entryCheck": "OPTIONAL", "contributors": ["high-risk-or-pep"], "verification": ["IDENTITY", "SANCTIONS", "PEP"], "approvalRequired": true}', 'native-form', '{"PEP": "simulated", "IDENTITY": "simulated", "SANCTIONS": "simulated"}', 'default', '{"minOwnerAge": 18, "reviewMonths": {"LOW": 36, "HIGH": 12, "MEDIUM": 24}, "uboThresholdPct": 25, "checkTimeoutHours": 24}', true, true, '2026-11-14 02:13:24.21602+00');


--
-- Data for Name: kyc_rules; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.kyc_rules VALUES ('d07a0335-1ce5-4a1f-867c-a764bfabe7d3', '7e44bd0c-1726-4487-861e-a560338c58c3', 'REG_ADDRESS_EXACT_ONE', 'SUBMIT', 'LIST_COUNT', '{"max": 1, "min": 1, "field": "addressType", "value": ["REGISTERED"], "section": "addresses"}', 'Exactly one registered office address is required.');
INSERT INTO public.kyc_rules VALUES ('4b4776ff-a0e3-420f-b59c-e0eddcddbb35', '7e44bd0c-1726-4487-861e-a560338c58c3', 'OPERATING_ADDRESS_AT_MOST_ONE', 'SUBMIT', 'LIST_COUNT', '{"max": 1, "min": 0, "field": "addressType", "value": ["OPERATING"], "section": "addresses"}', 'Add at most one operational / store address.');
INSERT INTO public.kyc_rules VALUES ('37bca7d4-3377-4902-a422-72bb302da63b', '7e44bd0c-1726-4487-861e-a560338c58c3', 'OWNERSHIP_SUM_MAX', 'SUBMIT', 'SUM_MAX', '{"max": 100, "field": "ownershipPercentage", "section": "ubos"}', 'The combined ownership percentages cannot exceed 100%.');
INSERT INTO public.kyc_rules VALUES ('2bd37664-d9ad-468c-886d-8b2fbed48bad', '7e44bd0c-1726-4487-861e-a560338c58c3', 'BENEFICIARY_NAME_MATCH', 'SUBMIT', 'NAME_MATCH', '{"against": ["businessProfile.legalBusinessName"], "section": "bankAccounts", "severity": "ERROR", "nameField": "beneficiaryName", "soleProprietor": {"field": "fullLegalName", "section": "ubos"}}', 'The account holder name must match the legal business name or the sole proprietor''s name.');
INSERT INTO public.kyc_rules VALUES ('83cc3996-5a48-464a-bfd5-6e06f87e31ca', '7e44bd0c-1726-4487-861e-a560338c58c3', 'UNIQUE_REGISTRATION', 'SUBMIT', 'UNIQUE_IN_PLATFORM', '{"tag": "REG_NUMBER", "field": "registrationNumber", "section": "businessProfile", "severity": "ERROR", "normalize": "PUNCT"}', 'This registration number is already used by another active organization.');
INSERT INTO public.kyc_rules VALUES ('1a8a6464-5bec-4c7a-b3c3-dbea14b2c228', '7e44bd0c-1726-4487-861e-a560338c58c3', 'UNIQUE_TIN', 'SUBMIT', 'UNIQUE_IN_PLATFORM', '{"tag": "TAX_ID", "field": "taxIdentificationNumber", "section": "businessProfile", "severity": "ERROR"}', 'This tax identification number is already used by another active organization.');
INSERT INTO public.kyc_rules VALUES ('e11c7dff-a998-4764-88f9-3eaffc0b1bcb', '7e44bd0c-1726-4487-861e-a560338c58c3', 'OWNER_ID_UNIQUE', 'SUBMIT', 'UNIQUE_IN_PLATFORM', '{"tag": "UBO_ID_NUMBER", "field": "identificationNumber", "section": "ubos", "severity": "WARNING"}', 'This identity number already appears in the platform (warning).');


--
-- PostgreSQL database dump complete
--
