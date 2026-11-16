/**
 * Onboard an organization (org.onboard-child): the signed-in org submits a new organization below
 * itself, of a type it may onboard, with its first administrator. The new org waits in the
 * onboarding queue for approval. GET /api/orgs/me/grantable · POST /api/orgs/me/children.
 */
import { defineWidget, span } from '../../core/manifest';
import { ORG_CHILDREN_SUBMIT, ORG_CHILDREN_SUBMIT_REQUEST, ORG_GRANTABLE_GET } from './onboard-child.contract';

export type OrgOnboardChildConfig = Record<string, never>;

export default defineWidget<OrgOnboardChildConfig>({
  id: 'org.onboard-child',
  version: '1.0.0',
  label: 'Onboard an organization',
  description: 'Submit a new organization below yours, with its first administrator, for approval.',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: { all: ['org.onboarding:create', 'org.rights:read'] },
  actions: [
    { id: 'submit', label: 'Submit for approval', cell: 'org.onboarding:create', command: 'org.children.submit' },
  ],
  sources: ['org.grantable.get'],
  commands: ['org.children.submit'],
  operations: ['view', 'create'],
  commandOperations: { 'org.children.submit': 'create' },
  contracts: {
    'org.grantable.get': ORG_GRANTABLE_GET,
    'org.children.submit': ORG_CHILDREN_SUBMIT,
  },
  requests: { 'org.children.submit': ORG_CHILDREN_SUBMIT_REQUEST },
  publishes: ['orgId'],
  config: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 8, 12) },
  hideOn: ['forbidden'],
  load: () => import('./onboard-child.component').then((m) => m.OrgOnboardChildWidget),
});
