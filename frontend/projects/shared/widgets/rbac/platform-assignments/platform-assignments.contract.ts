/**
 * Contracts of rbac.platform-assignments (PlatformRbacController#assignments, #assign, #revoke, #users, #roles).
 */
import { s, type Infer } from '../../core/shape';
import { RBAC_NEW_ROLE_PLATFORM_ROLES } from '../new-role/new-role.contract';

const ASSIGNMENT = s.object('RbacAssignmentView', {
  id: s.nullable(s.string()),
  subject: s.nullable(s.string()),
  role: s.nullable(s.string()),
  tenant: s.nullable(s.string()),
  scope: s.nullable(s.string()),
  status: s.nullable(s.string()),
});

/** GET /api/admin/rbac/assignments?status → List<RbacAssignmentView> (rbac.assignment:read). */
export const PLATFORM_ASSIGNMENTS_LIST = s.array(ASSIGNMENT);
export type PlatformAssignmentsList = Infer<typeof PLATFORM_ASSIGNMENTS_LIST>;
export type PlatformAssignment = PlatformAssignmentsList[number];

/** GET /api/admin/rbac/users → List<RbacUserView> (rbac.user:read). */
export const PLATFORM_ASSIGNMENTS_USERS = s.array(s.object('RbacUserView', {
  id: s.nullable(s.string()),
  displayName: s.nullable(s.string()),
  orgId: s.nullable(s.string()),
  kind: s.nullable(s.string()),
  status: s.nullable(s.string()),
}));
export type PlatformAssignmentsUsers = Infer<typeof PLATFORM_ASSIGNMENTS_USERS>;

/** GET /api/admin/rbac/roles (rbac.role:read). */
export const PLATFORM_ASSIGNMENTS_ROLES = RBAC_NEW_ROLE_PLATFORM_ROLES;
export type PlatformAssignmentsRoles = Infer<typeof PLATFORM_ASSIGNMENTS_ROLES>;

/** POST /api/admin/rbac/assignments → RbacAssignmentView (rbac.assignment:create). */
export const PLATFORM_ASSIGNMENTS_ASSIGN = ASSIGNMENT;
/** Dtos.RbacAssignRequest. */
export const PLATFORM_ASSIGNMENTS_ASSIGN_REQUEST = s.object('RbacAssignRequest', {
  subject: s.nullable(s.string()),
  role: s.nullable(s.string()),
  tenant: s.nullable(s.string()),
  scope: s.nullable(s.string()),
  expiresAt: s.nullable(s.string()),
  reason: s.nullable(s.string()),
});
export type PlatformAssignmentsAssignRequest = Infer<typeof PLATFORM_ASSIGNMENTS_ASSIGN_REQUEST>;

/** DELETE /api/admin/rbac/assignments/{id}?reason → RbacAssignmentView (rbac.assignment:administer). */
export const PLATFORM_ASSIGNMENTS_REVOKE = ASSIGNMENT;
