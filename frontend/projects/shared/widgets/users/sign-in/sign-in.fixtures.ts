/**
 * Fixture scenarios for users.sign-in (credential users and the three credential commands).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { CredentialUser } from './sign-in.contract';

export const PEOPLE: CredentialUser[] = [
  { id: 'u-anna', name: 'Anna Kambatu', username: 'anna.k', phone: '+264811234567', status: 'ACTIVE', org_role: 'ADMIN',
    subject: 'tp-anna', link_status: 'ACTIVE', idp_enabled: true },
  { id: 'u-petrus', name: 'Petrus Shiimi', username: 'petrus.s', phone: '+264812223344', status: 'PENDING', org_role: 'STAFF',
    subject: 'tp-petrus', link_status: 'PENDING', idp_enabled: true },
  { id: 'u-ngari', name: 'Ngarikutuke Tjimune', username: null, phone: '+264813334455', status: 'PENDING', org_role: 'STAFF',
    subject: null, link_status: null, idp_enabled: false },
];

const answer = (scope?: string) => (p: Readonly<Record<string, unknown>>) =>
  (scope ? { enqueued: true, user_id: String(p['uid']), scope } : { enqueued: true, user_id: String(p['uid']) });

const LONG = 'Ndapewa Ndinelago Shikongo-Haufiku of the Oshakati Regional Wholesale Cooperative';

export default defineFixtures({
  default: {
    'users.sign-in.list': PEOPLE,
    'users.sign-in.resend-link': answer('LINK'),
    'users.sign-in.reset': answer('FULL'),
    'users.sign-in.revoke-sessions': answer(),
  },
  empty: { 'users.sign-in.list': [] },
  error: { 'users.sign-in.list': fail(500, { title: 'Server error', detail: 'Identity service unavailable' }) },
  longText: { 'users.sign-in.list': PEOPLE.map((p) => ({ ...p, name: LONG })), 'users.sign-in.reset': answer('FULL') },
  refused: { 'users.sign-in.list': PEOPLE,
    'users.sign-in.reset': fail(409, { title: 'Conflict', detail: 'User has no delivered IdP credential to act on', code: 'NO_CREDENTIAL' }) },
  forbidden: { 'users.sign-in.list': fail(403, { title: 'Forbidden', detail: 'Only an admin', code: 'ORG_ADMIN_REQUIRED' }) },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
