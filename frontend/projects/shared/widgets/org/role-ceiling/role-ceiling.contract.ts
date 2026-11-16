/**
 * Contracts of org.role-ceiling (OrgAccessController#ceiling, #setCeiling → OrgRoleCeilingService#view).
 */
import { s, type Infer } from '../../core/shape';

/** OrgRoleCeilingService#view (a Map on the server; every key is always put). */
export const ROLE_CEILING_VIEW = s.object('RoleCeilingView', {
  orgId: s.string(),
  explicit: s.boolean(),
  inheritedFrom: s.nullable(s.string()),
  unrestricted: s.boolean(),
  roles: s.array(s.string()),
  delegableRoles: s.array(s.string()),
  baselineRoles: s.array(s.string()),
  parentHandDown: s.array(s.string()),
  catalogue: s.array(s.object('CatalogueRole', { name: s.string(), description: s.nullable(s.string()) })),
});
export type RoleCeilingView = Infer<typeof ROLE_CEILING_VIEW>;

/** GET /api/orgs/{id}/role-ceiling (org.rights:read). */
export const ORG_CEILING_GET = ROLE_CEILING_VIEW;
/** PUT /api/orgs/{id}/role-ceiling (org.rights:administer) → the ceiling as it now is. */
export const ORG_CEILING_SET = ROLE_CEILING_VIEW;
/** Dtos.RoleCeilingRequest: inherit=true drops the org's own ceiling and follows its parent's. */
export const ORG_CEILING_SET_REQUEST = s.object('RoleCeilingRequest', {
  roles: s.nullable(s.array(s.string())),
  delegableRoles: s.nullable(s.array(s.string())),
  inherit: s.nullable(s.boolean()),
});
export type OrgCeilingSetRequest = Infer<typeof ORG_CEILING_SET_REQUEST>;
