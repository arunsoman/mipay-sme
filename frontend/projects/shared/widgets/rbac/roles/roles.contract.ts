/**
 * Contracts of rbac.roles. The list is the same answer rbac.new-role reads (platform roles in
 * PLATFORM, the org's view in org portals), so it reuses those contracts.
 */
import { s, type Infer } from '../../core/shape';
import {
  RBAC_NEW_ROLE_ORG_CREATE_REQUEST, RBAC_NEW_ROLE_ORG_ROLES, RBAC_NEW_ROLE_PLATFORM_CREATE, RBAC_NEW_ROLE_PLATFORM_ROLES,
  RBAC_NEW_ROLE_ROLES,
} from '../new-role/new-role.contract';

/** GET /api/admin/rbac/roles (PLATFORM) or /api/orgs/{id}/roles (org portals) - rbac.role:read. */
export const RBAC_ROLES_LIST = RBAC_NEW_ROLE_ROLES;
export type PlatformRole = Infer<typeof RBAC_NEW_ROLE_PLATFORM_ROLES>[number];
export type OrgRolesView = Infer<typeof RBAC_NEW_ROLE_ORG_ROLES>;
export type OrgRole = OrgRolesView['roles'][number];

/** The org's own role as RBAC answers a change (Map on the server; same shape as a listed role). */
const ORG_ROLE_ANSWER = s.object('OrgRole', {
  name: s.string(),
  description: s.nullable(s.string()),
  kind: s.nullable(s.string()),
  base_roles: s.nullable(s.array(s.string())),
  permissions: s.array(s.string()),
});

/** PUT /api/admin/rbac/roles/{name} → RbacRoleView, or PUT /api/orgs/{id}/roles/{name} → the org role (rbac.role:modify). */
export const RBAC_ROLES_UPDATE = s.oneOf(RBAC_NEW_ROLE_PLATFORM_CREATE, ORG_ROLE_ANSWER);

/** PLATFORM sends Dtos.RbacRoleUpdate {permissions}; org portals send OrgRoleBody. */
export const RBAC_ROLE_UPDATE_PLATFORM_REQUEST = s.object('RbacRoleUpdate', {
  permissions: s.nullable(s.array(s.string())),
});
export type RbacRoleUpdatePlatformRequest = Infer<typeof RBAC_ROLE_UPDATE_PLATFORM_REQUEST>;
export type RbacRoleUpdateOrgRequest = Infer<typeof RBAC_NEW_ROLE_ORG_CREATE_REQUEST>;
/** The org body first: a platform body lacks its fields, so it falls through to RbacRoleUpdate (an org body would match RbacRoleUpdate with warnings). */
export const RBAC_ROLES_UPDATE_REQUEST = s.oneOf(RBAC_NEW_ROLE_ORG_CREATE_REQUEST, RBAC_ROLE_UPDATE_PLATFORM_REQUEST);

/** DELETE /api/admin/rbac/roles/{name} or /api/orgs/{id}/roles/{name} (rbac.role:delete): Map<String,Object>, untyped. */
export const RBAC_ROLES_DELETE = s.any();
