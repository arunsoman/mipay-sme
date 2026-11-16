/**
 * Onboarding rights (org.rights): which organization types an organization may onboard, and which
 * the organizations it onboards may onboard in turn. An organization above it may narrow them.
 * GET/PUT /api/orgs/{id}/rights.
 */
import { defineWidget, span } from '../../core/manifest';
import { ORG_RIGHTS_GET, ORG_RIGHTS_NARROW, ORG_RIGHTS_NARROW_REQUEST } from './rights.contract';

export type OrgRightsConfig = Record<string, never>;

export default defineWidget<OrgRightsConfig>({
  id: 'org.rights',
  version: '1.0.0',
  label: 'Onboarding rights',
  description: 'Which organization types an organization may onboard, and what those may onboard; narrowed from above.',
  category: 'Access',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.rights:read'],
  actions: [
    { id: 'narrow', label: 'Narrow the rights', cell: 'org.rights:administer', command: 'org.rights.narrow' },
  ],
  sources: ['org.rights.get'],
  commands: ['org.rights.narrow'],
  operations: ['view', 'modify'],
  commandOperations: { 'org.rights.narrow': 'modify' },
  contracts: { 'org.rights.get': ORG_RIGHTS_GET, 'org.rights.narrow': ORG_RIGHTS_NARROW },
  requests: { 'org.rights.narrow': ORG_RIGHTS_NARROW_REQUEST },
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  config: {},
  size: { xs: span.xs(4), sm: span.sm(4, 8), md: span.md(4, 6, 12), lg: span.lg(3, 4, 12) },
  hideOn: ['forbidden'],
  load: () => import('./rights.component').then((m) => m.OrgRightsWidget),
});
