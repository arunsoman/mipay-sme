-- V6: KYC outcome on org-typed organizations (MANUFACTURER/DISTRIBUTOR/LENDER).
-- Legacy supplier/merchant orgs keep null kyc_status (their KYC lives on the entity row).
alter table organizations add column if not exists kyc_status varchar(16)
    check (kyc_status in ('PENDING','VERIFIED','REJECTED'));
alter table organizations add column if not exists kyc_risk_rating varchar(16)
    check (kyc_risk_rating in ('LOW','MEDIUM','HIGH'));
alter table organizations add column if not exists kyc_next_review_date timestamp(6) with time zone;
create index if not exists ix_org_kyc_status on organizations (kyc_status);
