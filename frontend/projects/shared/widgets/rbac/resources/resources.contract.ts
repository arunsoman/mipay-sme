/**
 * Contract of rbac.resources (PlatformRbacController#resources).
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/admin/rbac/resources → List<Dtos.RbacResourceView> (rbac.role:read). */
export const RBAC_RESOURCES_LIST = s.array(s.object('RbacResourceView', {
  id: s.nullable(s.string()),
  service: s.nullable(s.string()),
  description: s.nullable(s.string()),
  used: s.nullable(s.array(s.string())),
  tenantTypes: s.nullable(s.array(s.string())),
  endpoints: s.nullable(s.array(s.object('RbacEndpointView', {
    verb: s.nullable(s.string()),
    method: s.nullable(s.string()),
    path: s.nullable(s.string()),
    handler: s.nullable(s.string()),
  }))),
}));
export type RbacResourcesList = Infer<typeof RBAC_RESOURCES_LIST>;
export type RbacResource = RbacResourcesList[number];
