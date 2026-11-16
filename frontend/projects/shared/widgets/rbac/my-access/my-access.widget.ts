/**
 * My access (rbac.my-access): the signed-in person's roles, and what those roles let them do laid
 * out like the role builder (rbac.new-role) but read-only - menu › submenu › widget against View,
 * Create, Modify, Approve, ticked where they hold it. Only widgets with at least one tick show.
 *
 * GET /api/orgs/me/access needs only a session; the widget catalogue behind the grid does not
 * (admin.access:read in PLATFORM, rbac.role:read in the org portals), so the widget is gated on those.
 */
import { defineWidget, span } from '../../core/manifest';
import { RBAC_MY_ACCESS_GET, RBAC_MY_ACCESS_WIDGETS } from './my-access.contract';

export type RbacMyAccessConfig = Record<string, never>;

export default defineWidget<RbacMyAccessConfig>({
  id: 'rbac.my-access',
  version: '2.0.0',
  label: 'My access',
  description: 'What your roles let you do, widget by widget: View, Create, Modify, Approve (read-only).',
  category: 'Access',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  // the widget catalogue: rbac.role:read in an org portal, admin.access:read in PLATFORM
  requires: ['rbac.role:read', 'admin.access:read'],
  actions: [],
  sources: ['rbac.my-access.get', 'rbac.my-access.widgets'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'rbac.my-access.get': RBAC_MY_ACCESS_GET, 'rbac.my-access.widgets': RBAC_MY_ACCESS_WIDGETS },
  requests: {},
  config: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./my-access.component').then((m) => m.RbacMyAccessWidget),
});
