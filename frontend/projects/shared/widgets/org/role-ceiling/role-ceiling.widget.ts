/**
 * Role ceiling (org.role-ceiling): the roles an organization's people may hold and the ones it may
 * hand down to organizations it onboards. Set by an organization above it (or MiPay staff).
 * GET/PUT /api/orgs/{id}/role-ceiling.
 */
import { defineWidget, span } from '../../core/manifest';
import { ORG_CEILING_GET, ORG_CEILING_SET, ORG_CEILING_SET_REQUEST } from './role-ceiling.contract';

export type OrgRoleCeilingConfig = Record<string, never>;

export default defineWidget<OrgRoleCeilingConfig>({
  id: 'org.role-ceiling',
  version: '1.0.0',
  label: 'Role ceiling',
  description: 'The roles an organization may use and hand down; set by an organization above it.',
  category: 'Access',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.rights:read'],
  actions: [
    { id: 'save', label: 'Save the ceiling', cell: 'org.rights:administer', command: 'org.ceiling.set' },
  ],
  sources: ['org.ceiling.get'],
  commands: ['org.ceiling.set'],
  operations: ['view', 'modify'],
  commandOperations: { 'org.ceiling.set': 'modify' },
  contracts: { 'org.ceiling.get': ORG_CEILING_GET, 'org.ceiling.set': ORG_CEILING_SET },
  requests: { 'org.ceiling.set': ORG_CEILING_SET_REQUEST },
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  config: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./role-ceiling.component').then((m) => m.OrgRoleCeilingWidget),
});
