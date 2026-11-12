/** Extended RBAC (docs/plans/extended-rbac.md): role ceilings, org-defined roles, role assignments. */

export interface CatalogueRole { name: string; description: string; }

/** GET/PUT /api/orgs/{id}/role-ceiling */
export interface RoleCeilingView {
  orgId: string;
  explicit: boolean;
  inheritedFrom: string | null;
  unrestricted: boolean;
  roles: string[];
  delegableRoles: string[];
  baselineRoles: string[];
  /** What the org above may give this org (the editor's choices). */
  parentHandDown: string[];
  catalogue: CatalogueRole[];
}

/** A role as RBAC shows it (roleView), with the org's flags. */
export interface RbacRole {
  name: string;
  description: string;
  kind: string;
  approval: string;
  inherits: string[];
  owner_tenant: string | null;
  base_roles: string[];
  grid: Record<string, string[]>;
  /** Effective cells "row:verb" (own + inherited). */
  permissions: string[];
  in_ceiling?: boolean;
  delegable?: boolean;
  baseline?: boolean;
}

/** GET /api/orgs/{id}/roles */
export interface OrgRolesView {
  ceiling: { unrestricted: boolean; explicit: boolean; inherited_from: string | null; roles: string[]; delegable: string[]; baseline: string[] };
  roles: RbacRole[];
  org_roles: RbacRole[];
  /** app.rbac.mode on the backend: shadow | enforce | off */
  rbac_mode?: string;
}

export interface RoleAssignment {
  id: string;
  subject: string;
  role: string;
  tenant: string;
  scope: string;
  status: string;
  expires_at: string | null;
  requested_by: string;
  requested_at: string;
  approved_by: string | null;
  userName?: string | null;
  username?: string | null;
}

export interface OrgMember { id: string; name: string; username: string; status: string; }

export const RBAC_HINTS: Record<string, string> = {
  ROLE_OUTSIDE_CEILING: 'that role is outside this organization’s role ceiling',
  ROLE_NOT_OWNED: 'an organization’s own roles can only be given within that organization',
  ROLES_EXCEED_CEILING: 'you can only pass on roles your organization may hand down',
  ROLES_INVALID: 'a role can only be handed down if the organization may use it',
  EXCEEDS_BASE_ROLES: 'a custom role can only contain permissions of the roles it is built on',
  SOD_CONFLICT: 'this would break a separation-of-duty rule',
  LAST_ADMIN: 'an organization keeps at least one administrator',
  four_eyes: 'a second person must approve this; requesters and grantees never approve their own request',
  ANCESTOR_REQUIRED: 'only an organization above it may change its role ceiling',
  ORG_ADMIN_REQUIRED: 'only an administrator of this organization or one above it may do this',
  RBAC_UNAVAILABLE: 'the access service is not reachable right now',
  insufficient_permission: 'your account does not hold the permission for this',
};

/** "merchant.inventory:create" → ["merchant.inventory", "create"] */
export function splitCell(c: string): [string, string] {
  const i = c.lastIndexOf(':');
  return [c.slice(0, i), c.slice(i + 1)];
}

/** Cells grouped by row, rows sorted, verbs in grid order. */
export function groupCells(cells: Iterable<string>): { row: string; verbs: string[] }[] {
  const order = ['read', 'create', 'modify', 'delete', 'submit', 'authorize', 'execute', 'export', 'administer', 'simulate'];
  const m = new Map<string, Set<string>>();
  for (const c of cells) {
    const [row, verb] = splitCell(c);
    if (!m.has(row)) m.set(row, new Set());
    m.get(row)!.add(verb);
  }
  return [...m.keys()].sort().map((row) => ({ row, verbs: order.filter((v) => m.get(row)!.has(v)) }));
}

/** "stock_clerk@<org id>" → "stock_clerk" */
export function roleLabel(name: string): string {
  const i = name.indexOf('@');
  return i < 0 ? name : name.slice(0, i);
}
