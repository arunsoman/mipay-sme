/**
 * Onboard an organization (org.onboard-child): ONE step (kyc-filed.md). The signed-in org fills
 * the new organization's KYC on the five tabs (Business Profile & General Information,
 * Operational & Store Details, Registered & Operational Address, Ultimate Beneficial Owners (UBO)
 * & Key Management, Settlement & Payout Bank Account Details) and submits it: that one call
 * creates the org, the administrator the system makes for it (login = the Legal Business Name,
 * + the Business Registration / Corporate ID Number when the name is taken) and the submitted KYC.
 * GET /api/orgs/me/grantable · GET /api/kyc/schema/types/{orgType} · GET /api/kyc/lists/{code}
 * · POST /api/orgs/me/children/with-kyc.
 */
import { defineWidget, span } from '../../core/manifest';
import { KYC_LIST_GET, KYC_SCHEMA_GET } from '../../kyc/application/kyc-application.contract';
import { ORG_CHILDREN_ONBOARD, ORG_CHILDREN_ONBOARD_REQUEST, ORG_GRANTABLE_GET } from './onboard-child.contract';

export type OrgOnboardChildConfig = Record<string, never>;

export default defineWidget<OrgOnboardChildConfig>({
  id: 'org.onboard-child',
  version: '1.1.0',
  label: 'Onboard an organization',
  description: 'One step: fill the new organization’s KYC on five tabs and submit; the org and its administrator are created with it.',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: { all: ['org.onboarding:create', 'org.rights:read', 'kyc.form:read'] },
  actions: [
    { id: 'submit', label: 'Submit for review', cell: 'org.onboarding:create', command: 'org.children.onboard' },
  ],
  sources: ['org.grantable.get', 'kyc.schema.type.get', 'kyc.list.get'],
  commands: ['org.children.onboard'],
  operations: ['view', 'create'],
  commandOperations: { 'org.children.onboard': 'create' },
  contracts: {
    'org.grantable.get': ORG_GRANTABLE_GET,
    'kyc.schema.type.get': KYC_SCHEMA_GET,
    'kyc.list.get': KYC_LIST_GET,
    'org.children.onboard': ORG_CHILDREN_ONBOARD,
  },
  requests: { 'org.children.onboard': ORG_CHILDREN_ONBOARD_REQUEST },
  publishes: ['orgId'],
  config: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(8, 12) },
  hideOn: ['forbidden'],
  load: () => import('./onboard-child.component').then((m) => m.OrgOnboardChildWidget),
});
