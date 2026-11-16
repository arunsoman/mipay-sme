/**
 * Contract of rbac.my-access (MyAccessController#myAccess).
 */
import { s, type Infer } from '../../core/shape';
import { RBAC_NEW_ROLE_WIDGETS } from '../new-role/new-role.contract';

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

/** GET /api/ui/widgets?portal= (PLATFORM, admin.access:read) or /api/orgs/{id}/ui/widgets?portal= (org portals,
 *  rbac.role:read): the registered widgets - the same catalogue the role builder reads. */
export const RBAC_MY_ACCESS_WIDGETS = RBAC_NEW_ROLE_WIDGETS;
export type RbacMyAccessWidgets = Infer<typeof RBAC_MY_ACCESS_WIDGETS>;
