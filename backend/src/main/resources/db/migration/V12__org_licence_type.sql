-- V12: organizations carry their licence type next to their country (KYC design §9:
-- "add country and licence_type, inherited from the parent at creation; the root is
-- NA/PSP"). V11 added country; Organization.licenceType (length 8) expects this column,
-- and Hibernate schema validation refuses to start without it.
alter table organizations add column if not exists licence_type varchar(8);
update organizations set licence_type = 'PSP' where licence_type is null;

-- entity types such as CLOSE_CORPORATION (17), SECTION_21_COMPANY (18) and
-- STATE_OWNED_ENTERPRISE (22) do not fit the original varchar(16): saving a KYC draft
-- with one of them failed with "value too long" (surfaced as a misleading DUPLICATE).
alter table kyc_applications alter column entity_type type varchar(32);
alter table kyc_profiles     alter column entity_type type varchar(32);
