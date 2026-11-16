/**
 * Sign-in access (admin.platform-access): every user's sign-in links and live sessions across the
 * platform, ending all of a user's sessions (an incident is recorded) and disabling one sign-in
 * link. MiPay only. GET /api/admin/access[/{userId}] · POST /api/admin/access/{userId}/revoke ·
 * POST /api/admin/access/links/{linkId}/disable.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  ADMIN_ACCESS_DETAIL, ADMIN_ACCESS_DISABLE, ADMIN_ACCESS_LIST, ADMIN_ACCESS_REVOKE, NO_BODY,
} from './platform-access.contract';

export interface AdminPlatformAccessConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<AdminPlatformAccessConfig>({
  id: 'admin.platform-access',
  version: '1.0.0',
  label: 'Sign-in access',
  description: 'Every user’s sign-in links and live sessions; end all of a user’s sessions or disable a sign-in link.',
  category: 'Users',
  contexts: ['PLATFORM'],
  requires: ['admin.access:read'],
  actions: [
    { id: 'revoke', label: 'End every session', cell: 'admin.access:administer', command: 'admin.access.revoke' },
    { id: 'disable-link', label: 'Disable a sign-in link', cell: 'admin.access:administer', command: 'admin.access.disable-link' },
  ],
  sources: ['admin.access.list', 'admin.access.detail'],
  commands: ['admin.access.revoke', 'admin.access.disable-link'],
  operations: ['view', 'approve'],
  commandOperations: { 'admin.access.revoke': 'approve', 'admin.access.disable-link': 'approve' },
  contracts: {
    'admin.access.list': ADMIN_ACCESS_LIST,
    'admin.access.detail': ADMIN_ACCESS_DETAIL,
    'admin.access.revoke': ADMIN_ACCESS_REVOKE,
    'admin.access.disable-link': ADMIN_ACCESS_DISABLE,
  },
  requests: { 'admin.access.revoke': NO_BODY, 'admin.access.disable-link': NO_BODY },
  publishes: ['userId'],
  config: {
    pageSize: { type: 'number', default: 15, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./platform-access.component').then((m) => m.AdminPlatformAccessWidget),
});
