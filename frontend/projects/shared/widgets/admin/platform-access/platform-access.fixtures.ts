/**
 * Fixture scenarios for admin.platform-access (AccessUserRow, AccessDetail, revoke/disable results).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { AccessUser, AdminAccessDetail } from './platform-access.contract';

export const USERS: AccessUser[] = [
  { userId: 'u-anna', phone: '+264811234567', role: 'ADMIN', status: 'ACTIVE', activeSessions: 2 },
  { userId: 'u-petrus', phone: '+264812223344', role: 'STAFF', status: 'PENDING', activeSessions: 0 },
];

export const DETAIL: AdminAccessDetail = {
  userId: 'u-anna', phone: '+264811234567', role: 'ADMIN', status: 'ACTIVE',
  links: [{ id: 'link-1', provider: 'trustplane', subject: 'tp-anna', status: 'ACTIVE', idpEnabled: true,
    activatedAt: '2026-10-02T08:00:00Z', lastLoginAt: '2026-11-15T07:45:00Z' }],
  sessions: [
    { id: 's-1', deviceFingerprint: 'chrome-android-7f3a', createdAt: '2026-11-15T07:45:00Z', expiresAt: '2026-11-15T19:45:00Z', live: true },
    { id: 's-2', deviceFingerprint: 'firefox-linux-19bc', createdAt: '2026-11-14T12:00:00Z', expiresAt: '2026-11-15T00:00:00Z', live: false },
  ],
};

export default defineFixtures({
  default: {
    'admin.access.list': USERS,
    'admin.access.detail': (p) => ({ ...DETAIL, userId: String(p['userId']) }),
    'admin.access.revoke': { revoked: 1, incidentId: 'inc-204' },
    'admin.access.disable-link': (p) => ({ linkId: String(p['linkId']), linkStatus: 'DISABLED' }),
  },
  empty: { 'admin.access.list': [] },
  error: { 'admin.access.list': fail(500, { title: 'Server error', detail: 'Identity service unavailable' }) },
  longText: { 'admin.access.list': USERS.map((u) => ({ ...u, userId: `${u.userId}-oshakati-regional-wholesale-cold-chain-cooperative` })),
    'admin.access.detail': DETAIL },
  refused: { 'admin.access.list': USERS, 'admin.access.detail': DETAIL,
    'admin.access.revoke': fail(409, { title: 'Conflict', detail: 'You cannot end your own sessions here' }),
    'admin.access.disable-link': fail(409, { title: 'Conflict', detail: 'Already disabled' }) },
  forbidden: { 'admin.access.list': fail(403, { title: 'Forbidden', detail: 'admin.access:read' }) },
}, { expect: { forbidden: 'forbidden' } });
