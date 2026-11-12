-- OA1 (org-access-model D-1/D-3/D-9): split yesterday's shared PLATFORM root into
--   * MINDLABS  - the MiNdLABS operator org (platform engineer / access administration /
--                 break-glass / the one-time TPTS bootstrap only), and
--   * TPTS      - the TPTS operating root that holds the ops staff (review, compliance,
--                 senior approval, recon).
-- Both are PLATFORM-typed orgs distinguished by a fixed entity marker, so platform role
-- assignments (scope all) and OpsService's org_type checks keep working unchanged.
-- Fixed ids ('org_mindlabs_platform' / 'org_tpts_platform') match OrgProvisioningService so
-- seeder, migration and the one-time bootstrap stay idempotent against each other.
--
-- Clean environments: this migration creates nothing (the seeder creates MINDLABS itself and
-- parks the seeded staff there; the TPTS org is created by the one-time bootstrap command
-- POST /api/admin/bootstrap/tpts, cell admin.bootstrap:administer, which refuses if TPTS
-- already exists). Environments that already carry users (afriqpay): both orgs are created
-- here and the staff is moved right away, so the bootstrap then correctly refuses.
--
-- RBAC note: platform role assignments are scope=all and stay valid across the move; the new
-- orgs appear as tenants on the next org-tree sync; the assignment tenant column still names
-- the root org for audit (re-homing is left to the quarterly access review).
--
-- Idempotent: re-running on a migrated database is a no-op; a re-run NEVER touches users
-- living in their own entity orgs (only rows still parked on the root shell move).

-- 1) the MINDLABS operator org (every environment that has a root shell)
INSERT INTO organizations (id, org_type, name, entity_id, parent_id, status, licence_type, created_by)
SELECT 'org_mindlabs_platform', 'PLATFORM', 'MiNdLABS (operator)', 'mindlabs', r.id, 'ACTIVE', 'PSP', 'migration:oa1'
FROM organizations r
WHERE r.org_type = 'PLATFORM' AND r.entity_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM organizations o WHERE o.org_type = 'PLATFORM' AND o.entity_id = 'mindlabs');

INSERT INTO org_closures (ancestor_id, descendant_id, depth)
SELECT o.id, o.id, 0 FROM organizations o
WHERE o.org_type = 'PLATFORM' AND o.entity_id = 'mindlabs'
ON CONFLICT DO NOTHING;

INSERT INTO org_closures (ancestor_id, descendant_id, depth)
SELECT r.id, m.id, 1 FROM organizations m
JOIN organizations r ON r.org_type = 'PLATFORM' AND r.entity_id IS NULL
WHERE m.org_type = 'PLATFORM' AND m.entity_id = 'mindlabs'
ON CONFLICT DO NOTHING;

-- rights: platform sub-orgs keep the root's onboarding abilities (D9: today's access unchanged)
INSERT INTO org_rights (id, org_id, target_type, kind, granted_by, granted_at)
SELECT 'right_' || o.entity_id || '_' || t.target || '_' || t.kind,
       o.id, t.target, t.kind, 'migration:oa1', now()
FROM organizations o
CROSS JOIN (VALUES ('MANUFACTURER','DIRECT'),('MANUFACTURER','CEILING'),('DISTRIBUTOR','DIRECT'),
                    ('DISTRIBUTOR','CEILING'),('SUPPLIER','DIRECT'),('SUPPLIER','CEILING'),
                    ('MERCHANT','DIRECT'),('MERCHANT','CEILING'),('LENDER','DIRECT'),('LENDER','CEILING'))
          AS t(target, kind)
WHERE o.org_type = 'PLATFORM' AND o.entity_id IN ('mindlabs')
  AND NOT EXISTS (SELECT 1 FROM org_rights r WHERE r.org_id = o.id AND r.target_type = t.target AND r.kind = t.kind);

-- 2) the TPTS operating root, only where TPTS staff actually exists (existing environments)
INSERT INTO organizations (id, org_type, name, entity_id, parent_id, status, licence_type, created_by)
SELECT 'org_tpts_platform', 'PLATFORM', 'TPTS (operating root)', 'tpts', r.id, 'ACTIVE', 'PSP', 'migration:oa1'
FROM organizations r
WHERE r.org_type = 'PLATFORM' AND r.entity_id IS NULL
  AND EXISTS (SELECT 1 FROM users u WHERE u.role = 'TPTS_OPS')
  AND NOT EXISTS (SELECT 1 FROM organizations o WHERE o.org_type = 'PLATFORM' AND o.entity_id = 'tpts');

INSERT INTO org_closures (ancestor_id, descendant_id, depth)
SELECT o.id, o.id, 0 FROM organizations o
WHERE o.org_type = 'PLATFORM' AND o.entity_id = 'tpts'
ON CONFLICT DO NOTHING;

INSERT INTO org_closures (ancestor_id, descendant_id, depth)
SELECT r.id, m.id, 1 FROM organizations m
JOIN organizations r ON r.org_type = 'PLATFORM' AND r.entity_id IS NULL
WHERE m.org_type = 'PLATFORM' AND m.entity_id = 'tpts'
ON CONFLICT DO NOTHING;

-- rights: as above for the operating root
INSERT INTO org_rights (id, org_id, target_type, kind, granted_by, granted_at)
SELECT 'right_' || o.entity_id || '_' || t.target || '_' || t.kind,
       o.id, t.target, t.kind, 'migration:oa1', now()
FROM organizations o
CROSS JOIN (VALUES ('MANUFACTURER','DIRECT'),('MANUFACTURER','CEILING'),('DISTRIBUTOR','DIRECT'),
                    ('DISTRIBUTOR','CEILING'),('SUPPLIER','DIRECT'),('SUPPLIER','CEILING'),
                    ('MERCHANT','DIRECT'),('MERCHANT','CEILING'),('LENDER','DIRECT'),('LENDER','CEILING'))
          AS t(target, kind)
WHERE o.org_type = 'PLATFORM' AND o.entity_id = 'tpts'
  AND NOT EXISTS (SELECT 1 FROM org_rights r WHERE r.org_id = o.id AND r.target_type = t.target AND r.kind = t.kind);

-- 3) move the staff (mirrors the bootstrap's move for clean environments)
--    TPTS ops staff (roles TPTS_OPS + the review/compliance/senior platform functions) go to
--    TPTS; MiNdLABS admins go to MINDLABS. Only users still parked on the root shell move.
UPDATE users u
SET org_id = 'org_tpts_platform'
WHERE u.role = 'TPTS_OPS'
  AND u.org_id = (SELECT r.id FROM organizations r WHERE r.org_type = 'PLATFORM' AND r.entity_id IS NULL)
  AND EXISTS (SELECT 1 FROM organizations o WHERE o.id = 'org_tpts_platform');

UPDATE users u
SET org_id = 'org_mindlabs_platform'
WHERE u.role = 'MINDLABS_ADMIN'
  AND u.org_id = (SELECT r.id FROM organizations r WHERE r.org_type = 'PLATFORM' AND r.entity_id IS NULL)
  AND EXISTS (SELECT 1 FROM organizations o WHERE o.id = 'org_mindlabs_platform');
