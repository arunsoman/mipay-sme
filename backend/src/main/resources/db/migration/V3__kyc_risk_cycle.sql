-- V3 (KYC & Compliance spec, sandbox core): risk-based review cycle + re-screening.
-- H2 test profile uses ddl-auto=update; this covers the Flyway-managed Postgres schema.

ALTER TABLE merchants
    ADD COLUMN IF NOT EXISTS kyc_risk_rating varchar(16),
    ADD COLUMN IF NOT EXISTS kyc_next_review_date date;

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS kyc_risk_rating varchar(16),
    ADD COLUMN IF NOT EXISTS kyc_next_review_date date;

-- Existing seeded merchants are sandbox-low risk with a 36-month review cycle.
UPDATE merchants SET kyc_risk_rating = 'LOW',
    kyc_next_review_date = CURRENT_DATE + INTERVAL '36 months'
WHERE kyc_risk_rating IS NULL AND kyc_status = 'VERIFIED';

UPDATE suppliers SET kyc_risk_rating = 'MEDIUM',
    kyc_next_review_date = CURRENT_DATE + INTERVAL '24 months'
WHERE kyc_risk_rating IS NULL AND approval_status = 'APPROVED';
