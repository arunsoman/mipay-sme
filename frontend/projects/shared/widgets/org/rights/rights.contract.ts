/**
 * Contracts of org.rights (OrgController#rights, #narrowRights).
 * Rights are org types: `direct` = the types this org may onboard, `ceiling` = the types the orgs
 * it onboards may onboard in turn. The server joins each list with commas.
 */
import { s, type Infer } from '../../core/shape';

export const ORG_RIGHTS_VIEW = s.object('OrgRightsView', {
  direct: s.nullable(s.string()),
  ceiling: s.nullable(s.string()),
  effDirect: s.nullable(s.string()),
  effCeiling: s.nullable(s.string()),
});
export type OrgRightsView = Infer<typeof ORG_RIGHTS_VIEW>;

/** GET /api/orgs/{id}/rights (org.rights:read). */
export const ORG_RIGHTS_GET = ORG_RIGHTS_VIEW;
/** PUT /api/orgs/{id}/rights (org.rights:administer): narrow only, by an org above it or MiPay staff. */
export const ORG_RIGHTS_NARROW = ORG_RIGHTS_VIEW;
/** Dtos.RightsPutRequest. */
export const ORG_RIGHTS_NARROW_REQUEST = s.object('RightsPutRequest', {
  rightsDirect: s.nullable(s.array(s.string())),
  rightsCeiling: s.nullable(s.array(s.string())),
});
export type OrgRightsNarrowRequest = Infer<typeof ORG_RIGHTS_NARROW_REQUEST>;
