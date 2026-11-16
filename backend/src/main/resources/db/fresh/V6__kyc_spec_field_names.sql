-- KYC v3 field names exactly as the merchant data capture specification words them
-- (kyc-filed.md, 2026-11-16). Labels and help only: no key, type or rule changes, so
-- applications already saved keep validating.

-- 3. Registered & Operational Address: the two address kinds are the spec's two fields
UPDATE public.kyc_list_items SET label = 'Registered Office Address'
 WHERE list_code = 'KYC_ADDRESS_TYPE' AND value = 'REGISTERED';
UPDATE public.kyc_list_items SET label = 'Operational / Store Address'
 WHERE list_code = 'KYC_ADDRESS_TYPE' AND value = 'OPERATING';

UPDATE public.kyc_fields f SET label = 'Street line'
  FROM public.kyc_sections s JOIN public.kyc_schema_versions v ON v.id = s.schema_version_id
 WHERE f.section_id = s.id AND v.code = 'NA-PSP-BUSINESS' AND v.version = 3
   AND s.key = 'addresses' AND f.key = 'streetLine';
UPDATE public.kyc_fields f SET label = 'State/Province'
  FROM public.kyc_sections s JOIN public.kyc_schema_versions v ON v.id = s.schema_version_id
 WHERE f.section_id = s.id AND v.code = 'NA-PSP-BUSINESS' AND v.version = 3
   AND s.key = 'addresses' AND f.key = 'region';

-- 4. Ultimate Beneficial Owners (UBO) & Key Management
UPDATE public.kyc_fields f SET help_text = 'Demographic identification.'
  FROM public.kyc_sections s JOIN public.kyc_schema_versions v ON v.id = s.schema_version_id
 WHERE f.section_id = s.id AND v.code = 'NA-PSP-BUSINESS' AND v.version = 3
   AND s.key = 'ubos' AND f.key IN ('dateOfBirth', 'nationality');
UPDATE public.kyc_fields f SET label = 'Government-Issued Identification Details: Document Type'
  FROM public.kyc_sections s JOIN public.kyc_schema_versions v ON v.id = s.schema_version_id
 WHERE f.section_id = s.id AND v.code = 'NA-PSP-BUSINESS' AND v.version = 3
   AND s.key = 'ubos' AND f.key = 'identificationType';
UPDATE public.kyc_fields f SET label = 'Government-Issued Identification Details: ID Number'
  FROM public.kyc_sections s JOIN public.kyc_schema_versions v ON v.id = s.schema_version_id
 WHERE f.section_id = s.id AND v.code = 'NA-PSP-BUSINESS' AND v.version = 3
   AND s.key = 'ubos' AND f.key = 'identificationNumber';
UPDATE public.kyc_fields f SET label = 'Government-Issued Identification Details: Expiry Date'
  FROM public.kyc_sections s JOIN public.kyc_schema_versions v ON v.id = s.schema_version_id
 WHERE f.section_id = s.id AND v.code = 'NA-PSP-BUSINESS' AND v.version = 3
   AND s.key = 'ubos' AND f.key = 'identificationExpiryDate';

-- Person 1's contact is the system-made administrator's (its login name comes from the business)
UPDATE public.kyc_fields f SET help_text = 'Mobile number. Person 1 is the contact of the administrator the system creates for the org.'
  FROM public.kyc_sections s JOIN public.kyc_schema_versions v ON v.id = s.schema_version_id
 WHERE f.section_id = s.id AND v.code = 'NA-PSP-BUSINESS' AND v.version = 3
   AND s.key = 'ubos' AND f.key = 'phoneNumber';
UPDATE public.kyc_fields f SET help_text = 'For Person 1, the administrator''s login name and activation link are sent here.'
  FROM public.kyc_sections s JOIN public.kyc_schema_versions v ON v.id = s.schema_version_id
 WHERE f.section_id = s.id AND v.code = 'NA-PSP-BUSINESS' AND v.version = 3
   AND s.key = 'ubos' AND f.key = 'emailAddress';
