/**
 * New role by widget (rbac.new-role): name a role, pick the roles it builds on, then for every
 * menu › submenu › widget of a portal tick what the role may do there - View, Create, Modify,
 * Approve. All four titles show for every widget (a sticky header keeps them on top while the
 * grid scrolls); only the operations a widget supports can be ticked.
 *
 * PLATFORM builds standard roles for any portal (POST /api/admin/rbac/roles); the org portals
 * build the org's own roles, carved out of the roles it builds on (POST /api/orgs/{id}/roles).
 * Built with the mipay-aruns-ui skill.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  RBAC_NEW_ROLE_CREATE, RBAC_NEW_ROLE_CREATE_REQUEST, RBAC_NEW_ROLE_ROLES, RBAC_NEW_ROLE_WIDGETS,
} from './new-role.contract';

export interface RbacNewRoleConfig {
  /** Give each ticked submenu its menu cell (nav.<portal>_<menu>_<screen>:read) so it shows in the sidebar. */
  menuCells: boolean;
}

export default defineWidget<RbacNewRoleConfig>({
  id: 'rbac.new-role',
  version: '1.0.0',
  label: 'New role',
  description: 'Create a role by ticking View, Create, Modify or Approve on every widget of a portal, menu by menu.',
  category: 'Access',
  contexts: ['PLATFORM', 'SUPPLIER', 'MANUFACTURER', 'DISTRIBUTOR', 'MERCHANT', 'LENDER'],
  // roles are read with rbac.role:read; in PLATFORM the widget catalogue is read with admin.access:read
  requires: ['rbac.role:read', 'admin.access:read'],
  actions: [
    { id: 'create', label: 'Create role', cell: 'rbac.role:create', command: 'rbac.new-role.create' },
  ],
  sources: ['rbac.new-role.widgets', 'rbac.new-role.roles'],
  commands: ['rbac.new-role.create'],
  operations: ['view', 'create'],
  commandOperations: { 'rbac.new-role.create': 'create' },
  contracts: {
    'rbac.new-role.widgets': RBAC_NEW_ROLE_WIDGETS,
    'rbac.new-role.roles': RBAC_NEW_ROLE_ROLES,
    'rbac.new-role.create': RBAC_NEW_ROLE_CREATE,
  },
  requests: { 'rbac.new-role.create': RBAC_NEW_ROLE_CREATE_REQUEST },
  // org portals: the org whose role this is - page selection first, then the user's own org
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'] } },
  config: {
    menuCells: { type: 'boolean', default: true, label: 'Give ticked submenus their menu cell' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./new-role.component').then((m) => m.RbacNewRoleWidget),
});
