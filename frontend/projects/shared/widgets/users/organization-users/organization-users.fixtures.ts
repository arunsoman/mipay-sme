/**
 * Fixture scenarios for users.organization-users. Every value matches the backend contracts:
 * OrgUserRow on reads, OrgStaffView on add, Message on deactivate, and the two confirmed
 * setup-link response modes.
 */
import { defineFixtures, fail } from '../../core/fixtures';

export const USERS = [
  {
    id: 'usr-8e12a2f0', name: 'Petrus Shiimi', username: 'p.shiimi', role: 'SUPPLIER', orgRole: 'STAFF',
    status: 'PENDING', phone: '+264811234567', email: 'petrus.shiimi@kalaharisupplies.example',
  },
  {
    id: 'usr-41bc903a', name: 'Anna Kambatu', username: 'a.kambatu', role: 'SUPPLIER', orgRole: 'ADMIN',
    status: 'ACTIVE', phone: '+264812345678', email: 'anna.kambatu@kalaharisupplies.example',
  },
  {
    id: 'usr-7c39dd41', name: 'Lukas Ndeitunga', username: 'l.ndeitunga', role: 'SUPPLIER', orgRole: 'STAFF',
    status: 'ACTIVE', phone: '+264813456789', email: 'lukas.ndeitunga@kalaharisupplies.example',
  },
  {
    id: 'usr-1f7ac803', name: 'Ngarikutuke Tjimune', username: 'n.tjimune', role: 'SUPPLIER', orgRole: 'STAFF',
    status: 'SUSPENDED', phone: '+264814567890', email: 'ngarikutuke.tjimune@kalaharisupplies.example',
  },
  {
    id: 'usr-92e450ca', name: 'Sarah Kuugongelwa', username: 's.kuugongelwa', role: 'SUPPLIER', orgRole: 'STAFF',
    status: 'ARCHIVED', phone: '+264815678901', email: 'sarah.kuugongelwa@kalaharisupplies.example',
  },
];

const CREATED = {
  id: 'usr-new-01', name: 'Tomas Amupadhi', username: 't.amupadhi', phone: '+264816789012',
  email: 'tomas.amupadhi@kalaharisupplies.example', role: 'SUPPLIER', orgRole: 'STAFF',
  status: 'PENDING', activatedAt: null,
};

const SETUP_LINK = {
  setupLink: 'https://idp.mipay.example/setup/once-7Gv2Kp9Lx4',
  expiresAt: '2026-11-18T14:30:00Z',
  delivered: true,
  deliveryMessage: 'Sent through the configured SMS and email channels',
};

const commands = {
  'users.organization.create': (params: Readonly<Record<string, unknown>>) => {
    const body = (params['body'] ?? {}) as Record<string, unknown>;
    const name = String(body['name'] ?? 'New person');
    return {
      ...CREATED,
      name,
      phone: String(body['phone'] ?? CREATED.phone),
      email: String(body['email'] ?? CREATED.email),
      orgRole: body['orgRole'] === 'ADMIN' ? 'ADMIN' : 'STAFF',
    };
  },
  'users.organization.setup-link': SETUP_LINK,
  'users.organization.deactivate': (params: Readonly<Record<string, unknown>>) => ({
    message: 'User deactivated; sessions deleted',
    label: String(params['id'] ?? ''),
  }),
};

const LONG = 'Kalahari Agricultural Trading, Storage and Distribution Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: { 'users.organization.list': USERS, ...commands },
  empty: { 'users.organization.list': [], ...commands },
  error: {
    'users.organization.list': fail(500, { title: 'Server error', detail: 'User directory unavailable' }),
    ...commands,
  },
  longText: {
    'users.organization.list': USERS.map((user) => ({ ...user, name: LONG, email: `operations@kalaharisupplies.example` })),
    ...commands,
  },
  forbidden: {
    'users.organization.list': fail(403, { code: 'NO_PERMISSION', title: 'Forbidden', detail: 'org.user:read' }),
    ...commands,
  },
  invalid: {
    'users.organization.list': USERS,
    ...commands,
    'users.organization.create': fail(400, {
      code: 'VALIDATION_FAILED', title: 'Validation failed', detail: 'Check the highlighted fields',
      fields: { email: 'must be a valid email address', phone: 'must be a Namibian mobile number' },
    }),
  },
  conflict: {
    'users.organization.list': USERS,
    ...commands,
    'users.organization.deactivate': fail(409, {
      code: 'LAST_ADMIN', title: 'Conflict', detail: 'Refusing to deactivate the last active ADMIN of the org',
    }),
  },
  setupConflict: {
    'users.organization.list': USERS,
    ...commands,
    'users.organization.setup-link': fail(409, {
      code: 'NO_CREDENTIAL', title: 'Conflict', detail: 'IdP enrolment is still pending; retry after it completes',
    }),
  },
  legacySetup: {
    'users.organization.list': USERS,
    ...commands,
    'users.organization.setup-link': {
      message: 'A new set-up link was issued',
      label: 'https://idp.mipay.example/setup/legacy-9Jm4Ts8Q',
    },
  },
  locked: {
    'users.organization.list': USERS.filter((user) => user.status === 'SUSPENDED' || user.status === 'ARCHIVED'),
    ...commands,
  },
}, {
  expect: { forbidden: 'forbidden', invalid: 'ready', conflict: 'ready', setupConflict: 'ready', locked: 'ready' },
});
