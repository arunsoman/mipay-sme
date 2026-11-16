/**
 * Contract of rbac.my-access (MyAccessController#myAccess).
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/orgs/me/access → MyAccess: the signed-in user's org, roles and held cells (any signed-in user). */
export const RBAC_MY_ACCESS_GET = s.object('MyAccess', {
  orgId: s.nullable(s.string()),
  orgName: s.nullable(s.string()),
  orgType: s.nullable(s.string()),
  permissions: s.nullable(s.array(s.object('HeldPermission', {
    id: s.nullable(s.string()),
    row: s.nullable(s.string()),
    verb: s.nullable(s.string()),
    rowDescription: s.nullable(s.string()),
  }))),
  roles: s.nullable(s.array(s.string())),
});
export type RbacMyAccessGet = Infer<typeof RBAC_MY_ACCESS_GET>;
