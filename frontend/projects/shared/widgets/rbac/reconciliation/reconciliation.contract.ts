/**
 * Contracts of rbac.reconciliation (PlatformRbacController#reconciliation, #mode).
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/admin/rbac/reconciliation/{orgId} → RbacReconciliationService.Report (rbac.assignment:read). */
export const RBAC_RECON_GET = s.object('Report', {
  orgId: s.nullable(s.string()),
  lifecycle: s.nullable(s.string()),
  users: s.integer(),
  activeCoreRoleAssignments: s.integer(),
  mismatches: s.integer(),
  rows: s.nullable(s.array(s.object('Mismatch', {
    userId: s.nullable(s.string()),
    username: s.nullable(s.string()),
    status: s.nullable(s.string()),
    coreRole: s.nullable(s.string()),
    expectedRole: s.nullable(s.string()),
    activeRbacRoles: s.nullable(s.array(s.string())),
    reason: s.nullable(s.string()),
  }))),
});
export type RbacReconGet = Infer<typeof RBAC_RECON_GET>;
export type Mismatch = NonNullable<RbacReconGet['rows']>[number];

/** GET /api/admin/rbac/mode (rbac.role:read): Map.of("mode", shadow | enforce | off). */
export const RBAC_MODE_GET = s.object('RbacMode', { mode: s.string() });
export type RbacModeGet = Infer<typeof RBAC_MODE_GET>;
