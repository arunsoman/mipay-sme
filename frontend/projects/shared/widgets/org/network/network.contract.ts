/**
 * Contracts of org.network (OrgController#meTree, #suspend, #lift).
 */
import { s, type Infer } from '../../core/shape';

const HOLD = s.object('OrgHoldRow', {
  id: s.nullable(s.string()),
  reason: s.nullable(s.string()),
  createdAt: s.nullable(s.dateTime()),
  createdBy: s.nullable(s.string()),
  releasedAt: s.nullable(s.dateTime()),
});

/**
 * GET /api/orgs/me/tree → Dtos.OrgNodeRow (org.tree:read): the caller's org and everything below it.
 * `children` are OrgNodeRow again; the contract checks the top node, the component reads children as the same type.
 */
export const ORG_TREE_GET = s.object('OrgNodeRow', {
  id: s.nullable(s.string()),
  type: s.nullable(s.string()),
  name: s.nullable(s.string()),
  lifecycle: s.nullable(s.string()),
  effectiveStatus: s.nullable(s.string()),
  activeHolds: s.integer(),
  holds: s.nullable(s.array(HOLD)),
  onboardedBy: s.nullable(s.string()),
  onboardedAt: s.nullable(s.dateTime()),
  effectiveRights: s.nullable(s.array(s.string())),
  users: s.nullable(s.array(s.object('OrgUserRow', {
    id: s.nullable(s.string()),
    name: s.nullable(s.string()),
    username: s.nullable(s.string()),
    role: s.nullable(s.string()),
    orgRole: s.nullable(s.string()),
    status: s.nullable(s.string()),
    phone: s.nullable(s.string()),
    email: s.nullable(s.string()),
  }))),
  userCount: s.integer(),
  subtree: s.nullable(s.object('OrgSubtreeCounts', {
    descendants: s.integer(),
    activeDescendants: s.integer(),
    users: s.integer(),
    activeUsers: s.integer(),
  })),
  rights: s.nullable(s.object('OrgRightsView', {
    direct: s.nullable(s.string()),
    ceiling: s.nullable(s.string()),
    effDirect: s.nullable(s.string()),
    effCeiling: s.nullable(s.string()),
  })),
  children: s.nullable(s.array(s.any())),
});
type TreeTop = Infer<typeof ORG_TREE_GET>;
/** One node, with its children typed as nodes. */
export type OrgNode = Omit<TreeTop, 'children'> & { children: OrgNode[] | null };
export type OrgHold = Infer<typeof HOLD>;

const SUSPENSION = s.object('SuspensionRow', {
  id: s.nullable(s.string()),
  entityType: s.nullable(s.string()),
  entityId: s.nullable(s.string()),
  reason: s.nullable(s.string()),
  suspendedAt: s.nullable(s.dateTime()),
  liftedAt: s.nullable(s.dateTime()),
});

/** POST /api/orgs/{id}/suspend → SuspensionRow (org.suspension:administer). */
export const ORG_SUSPEND = SUSPENSION;
export type OrgSuspend = Infer<typeof ORG_SUSPEND>;
/** Dtos.HoldRequest: the reason is required. */
export const ORG_SUSPEND_REQUEST = s.object('HoldRequest', { reason: s.string() });
export type OrgSuspendRequest = Infer<typeof ORG_SUSPEND_REQUEST>;

/** POST /api/orgs/suspensions/{sid}/lift → List<SuspensionRow> (org.suspension:administer). */
export const ORG_SUSPENSION_LIFT = s.array(SUSPENSION);
export type OrgSuspensionLift = Infer<typeof ORG_SUSPENSION_LIFT>;
export const ORG_SUSPENSION_LIFT_REQUEST = s.object('NoBody', {});
