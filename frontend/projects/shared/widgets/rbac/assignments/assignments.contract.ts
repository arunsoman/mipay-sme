/**
 * Contracts of rbac.assignments (OrgAccessController#members, #assignments, #assign, #revoke, #roles).
 * The assignment lists are Maps on the server (the RBAC service's answer, plus the user's names);
 * the shapes below are the fields rbac-api.ts RoleAssignment relies on.
 */
import { s, type Infer } from '../../core/shape';
import { RBAC_NEW_ROLE_ORG_ROLES } from '../new-role/new-role.contract';

/** GET /api/orgs/{id}/members (org.user:read): the org's people, for picking whom to give a role. */
export const ORG_MEMBERS = s.array(s.object('OrgMember', {
  id: s.string(),
  name: s.nullable(s.string()),
  username: s.nullable(s.string()),
  status: s.nullable(s.string()),
}));
export type OrgMembers = Infer<typeof ORG_MEMBERS>;
export const RBAC_ASSIGNMENTS_MEMBERS = ORG_MEMBERS;

/** One role assignment as RBAC keeps it. */
export const ROLE_ASSIGNMENT = s.object('RoleAssignment', {
  id: s.string(),
  subject: s.string(),
  role: s.string(),
  scope: s.nullable(s.string()),
  status: s.string(),
});

/** GET /api/orgs/{id}/role-assignments (rbac.assignment:read): with the user's name and login added. */
export const RBAC_ASSIGNMENTS_LIST = s.array(s.object('RoleAssignmentRow', {
  id: s.string(),
  subject: s.string(),
  role: s.string(),
  scope: s.nullable(s.string()),
  status: s.string(),
  userName: s.nullable(s.string()),
  username: s.nullable(s.string()),
}));
export type RbacAssignmentsList = Infer<typeof RBAC_ASSIGNMENTS_LIST>;
export type Assignment = RbacAssignmentsList[number];

/** GET /api/orgs/{id}/roles (rbac.role:read): the roles the org may give. */
export const RBAC_ASSIGNMENTS_ROLES = RBAC_NEW_ROLE_ORG_ROLES;
export type RbacAssignmentsRoles = Infer<typeof RBAC_ASSIGNMENTS_ROLES>;

/** POST /api/orgs/{id}/role-assignments (rbac.assignment:create) → the new assignment (ACTIVE or PENDING_APPROVAL). */
export const RBAC_ASSIGNMENTS_ASSIGN = ROLE_ASSIGNMENT;
export type RbacAssignmentsAssign = Infer<typeof RBAC_ASSIGNMENTS_ASSIGN>;
/** AssignBody: scope own | subtree; expiresAt RFC 3339. */
export const RBAC_ASSIGNMENTS_ASSIGN_REQUEST = s.object('AssignBody', {
  userId: s.nullable(s.string()),
  role: s.nullable(s.string()),
  scope: s.nullable(s.string()),
  expiresAt: s.nullable(s.string()),
  reason: s.nullable(s.string()),
});
export type RbacAssignmentsAssignRequest = Infer<typeof RBAC_ASSIGNMENTS_ASSIGN_REQUEST>;

/** POST /api/orgs/{id}/role-assignments/{assignmentId}/revoke (rbac.assignment:administer): Map, untyped. */
export const RBAC_ASSIGNMENTS_REVOKE = s.any();
export const RBAC_ASSIGNMENTS_REVOKE_REQUEST = s.object('ReasonBody', { reason: s.nullable(s.string()) });
export type RbacAssignmentsRevokeRequest = Infer<typeof RBAC_ASSIGNMENTS_REVOKE_REQUEST>;
