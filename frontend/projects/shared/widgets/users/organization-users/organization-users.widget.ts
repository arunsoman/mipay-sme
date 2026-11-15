/**
 * Organization users: the people in the signed-in user's own organization, with the
 * operations the org-user backend supports (add, issue a setup link, deactivate).
 * The endpoints are self-scoped, so this widget deliberately takes no org input.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  USERS_ORGANIZATION_CREATE,
  USERS_ORGANIZATION_CREATE_REQUEST,
  USERS_ORGANIZATION_DEACTIVATE,
  USERS_ORGANIZATION_DEACTIVATE_REQUEST,
  USERS_ORGANIZATION_LIST,
  USERS_ORGANIZATION_SETUP_LINK_RESPONSE,
  USERS_ORGANIZATION_SETUP_LINK_REQUEST,
} from './organization-users.contract';

export interface UsersOrganizationUsersConfig {
  /** Rows per page in the organization-user table. */
  pageSize: number;
}

export default defineWidget<UsersOrganizationUsersConfig>({
  id: 'users.organization-users',
  version: '1.0.0',
  label: 'Organization users',
  description: 'People in your organization, with controls to add a user, issue a setup link, and deactivate access.',
  category: 'Users',
  contexts: ['PLATFORM', 'LENDER', 'MERCHANT', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.user:read'],
  actions: [
    { id: 'add', label: 'Add person', cell: 'org.user:create', command: 'users.organization.create' },
    { id: 'setup-link', label: 'Issue setup link', cell: 'org.user:administer', command: 'users.organization.setup-link' },
    { id: 'deactivate', label: 'Deactivate person', cell: 'org.user:delete', command: 'users.organization.deactivate' },
  ],
  sources: ['users.organization.list'],
  commands: ['users.organization.create', 'users.organization.setup-link', 'users.organization.deactivate'],
  operations: ['view', 'create', 'approve'],
  commandOperations: {
    'users.organization.create': 'create',
    'users.organization.setup-link': 'approve',
    'users.organization.deactivate': 'approve',
  },
  contracts: {
    'users.organization.list': USERS_ORGANIZATION_LIST,
    'users.organization.create': USERS_ORGANIZATION_CREATE,
    'users.organization.setup-link': USERS_ORGANIZATION_SETUP_LINK_RESPONSE,
    'users.organization.deactivate': USERS_ORGANIZATION_DEACTIVATE,
  },
  requests: {
    'users.organization.create': USERS_ORGANIZATION_CREATE_REQUEST,
    'users.organization.setup-link': USERS_ORGANIZATION_SETUP_LINK_REQUEST,
    'users.organization.deactivate': USERS_ORGANIZATION_DEACTIVATE_REQUEST,
  },
  config: {
    pageSize: { type: 'number', default: 20, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  load: () => import('./organization-users.component').then((m) => m.UsersOrganizationUsersWidget),
});
