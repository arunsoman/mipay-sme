/**
 * Organization network (org.network): the signed-in org and every org below it, with status,
 * people and holds. Picking one publishes it (orgId) for the other widgets on the page, and shows
 * its details with Suspend / Lift. GET /api/orgs/me/tree · POST /api/orgs/{id}/suspend ·
 * POST /api/orgs/suspensions/{sid}/lift.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  ORG_SUSPEND, ORG_SUSPEND_REQUEST, ORG_SUSPENSION_LIFT, ORG_SUSPENSION_LIFT_REQUEST, ORG_TREE_GET,
} from './network.contract';

export interface OrgNetworkConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<OrgNetworkConfig>({
  id: 'org.network',
  version: '1.0.0',
  label: 'Organization network',
  description: 'Your organization and every organization below it, with status, people and holds; suspend or lift a hold.',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.tree:read'],
  actions: [
    { id: 'suspend', label: 'Suspend', cell: 'org.suspension:administer', command: 'org.suspend' },
    { id: 'lift', label: 'Lift a hold', cell: 'org.suspension:administer', command: 'org.suspension.lift' },
  ],
  sources: ['org.tree.get'],
  commands: ['org.suspend', 'org.suspension.lift'],
  operations: ['view', 'approve'],
  commandOperations: { 'org.suspend': 'approve', 'org.suspension.lift': 'approve' },
  contracts: {
    'org.tree.get': ORG_TREE_GET,
    'org.suspend': ORG_SUSPEND,
    'org.suspension.lift': ORG_SUSPENSION_LIFT,
  },
  requests: { 'org.suspend': ORG_SUSPEND_REQUEST, 'org.suspension.lift': ORG_SUSPENSION_LIFT_REQUEST },
  publishes: ['orgId', 'orgName'],
  config: {
    pageSize: { type: 'number', default: 15, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./network.component').then((m) => m.OrgNetworkWidget),
});
