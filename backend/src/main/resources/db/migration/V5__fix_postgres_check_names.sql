-- Repair for databases that already ran the original V4: V1 created the
-- users.role and suspensions.entity_type checks INLINE, so Postgres auto-named
-- them users_role_check / suspensions_entity_type_check. The original V4 only
-- dropped chk_users_role / chk_suspension_entity_type, leaving the restrictive
-- inline checks in place (DISTRIBUTOR/MANUFACTURER user roles and ORG/LENDER
-- suspensions were impossible on Postgres). Drop every spelling, then add the
-- widened checks. Fresh installs already get the fixed V4; this is idempotent.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role
    CHECK (role IN ('MERCHANT','SUPPLIER','LENDER','MANUFACTURER','DISTRIBUTOR','TPTS_OPS','MINDLABS_ADMIN'));

ALTER TABLE suspensions DROP CONSTRAINT IF EXISTS suspensions_entity_type_check;
ALTER TABLE suspensions DROP CONSTRAINT IF EXISTS chk_suspension_entity_type;
ALTER TABLE suspensions ADD CONSTRAINT chk_suspension_entity_type
    CHECK (entity_type IN ('MERCHANT','SUPPLIER','LENDER','ORG'));
