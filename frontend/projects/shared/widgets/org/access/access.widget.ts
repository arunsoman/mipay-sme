/**
 * What it can do (org.access, docs/plans/admin-console.md): an organization's features (the
 * menus of its portal) as switches, with presets and warnings. Saving builds the org's
 * organization_access role in RBAC, gives it to the org's admins and strips switched-off
 * features from the org's other roles. Which org: the page selection (the organizations list),
 * else the signed-in user's own org (org portals: read-only, an org never sets its own).
 */
import { defineWidget, span } from '../../core/manifest';
import { ORG_FEATURES, ORG_FEATURES_SET_REQUEST } from './access.contract';

export type OrgAccessConfig = Record<string, never>;

export default defineWidget<OrgAccessConfig>({
  id: 'org.access',
  version: '1.0.0',
  label: 'What it can do',
  description: "An organization's features (its portal menus) as switches, with presets and warnings such as \"Can't add users\".",
  category: 'Organizations',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.rights:read'],
  actions: [
    { id: 'save', label: 'Save features', cell: 'org.rights:administer', command: 'org.features.set' },
  ],
  sources: ['org.features.get'],
  commands: ['org.features.set'],
  operations: ['view', 'modify'],
  commandOperations: { 'org.features.set': 'modify' },
  contracts: { 'org.features.get': ORG_FEATURES, 'org.features.set': ORG_FEATURES },
  requests: { 'org.features.set': ORG_FEATURES_SET_REQUEST },
  inputs: {
    orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' },
  },
  config: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./access.component').then((m) => m.OrgAccessWidget),
});
