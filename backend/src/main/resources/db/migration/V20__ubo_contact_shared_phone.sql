-- V20: UBO contact details, and one phone number may serve several organizations.
--
-- 1. Section 4 (UBOs & Key Management) of NA-PSP-BUSINESS v3 gains Phone Number and Email
--    Address. Person 1's are the org administrator's login contact (onboarding creates the
--    admin from them; the login name is the legal business name + the MCC).
--    v3 is extended in place: drafts simply have two more fields to fill; nothing submitted
--    is re-validated.
-- 2. users.phone is no longer unique: the same person may administer several organizations.
--    Each login keeps a unique username; phone sign-in needs the number to match one account.

insert into kyc_fields (id, section_id, key, label, help_text, sort, data_type, list_code, required,
                        max_len, min_len, canonical_tag, sensitivity, mask)
select gen_random_uuid()::text, s.id, f.k, f.l, f.h, f.s, f.t, null, 'ALWAYS', f.mx, f.mn, f.tag, 'PII', null
from kyc_sections s join kyc_schema_versions v on v.id = s.schema_version_id,
(values
  ('phoneNumber', 'Phone Number', 'Mobile number. For Person 1 it is also the MiPay administrator''s login contact.', 45, 'PHONE', 20, 6, 'UBO_PHONE'),
  ('emailAddress', 'Email Address', 'For Person 1, the MiPay username and activation link are sent here.', 46, 'EMAIL', 200, 5, 'UBO_EMAIL')
) as f(k, l, h, s, t, mx, mn, tag)
where v.code = 'NA-PSP-BUSINESS' and v.version = 3 and s.key = 'ubos'
  and not exists (select 1 from kyc_fields x where x.section_id = s.id and x.key = f.k);

alter table users drop constraint if exists users_phone_key;
drop index if exists users_phone_key;
create index if not exists ix_users_phone on users (phone);
