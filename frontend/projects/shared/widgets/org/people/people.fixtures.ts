/**
 * Fixture scenarios for org.people (shape: a ListQuery page of PersonRow).
 * Answered by fixturePage, which searches, filters, sorts and pages like the server.
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { fixturePage } from '../../core/server-list';

const person = (id: string, name: string, username: string, orgRole: string, status: string,
                roles: string[], pendingRoles: string[] = []) => ({
  id, name, username, email: `${username}@kalaharisupplies.example`, phone: '+264811234567', orgRole, status, roles, pendingRoles,
});

export const PEOPLE = [
  person('u-1', 'Anna Kambatu', 'a.kambatu', 'ADMIN', 'ACTIVE', ['organization_access']),
  person('u-2', 'Petrus Shiimi', 'p.shiimi', 'STAFF', 'ACTIVE', ['cashier']),
  person('u-3', 'Lukas Ndeitunga', 'l.ndeitunga', 'STAFF', 'PENDING', [], ['stock_clerk']),
  person('u-4', 'Ngarikutuke Tjimune', 'n.tjimune', 'STAFF', 'SUSPENDED', []),
];
type Row = (typeof PEOPLE)[number];

/** The same fields as AdminConsoleController.PEOPLE_FIELDS. */
const search = (rows: Row[]) => fixturePage(rows, {
  search: [(p) => p.name, (p) => p.username, (p) => p.email, (p) => p.phone, (p) => p.roles],
  fields: { name: (p) => p.name, username: (p) => p.username, orgRole: (p) => p.orgRole, status: (p) => p.status,
            roles: (p) => p.roles, pendingRoles: (p) => p.pendingRoles },
});

const LONG = 'Ngarikutuke Tjimune-Kambatu-Shiimi of the Kalahari Agricultural Trading Cooperative';

export default defineFixtures({
  default: { 'org.people.search': search(PEOPLE) },
  empty: { 'org.people.search': search([]) },
  error: { 'org.people.search': fail(500, { title: 'Server error', detail: 'People unavailable' }) },
  longText: { 'org.people.search': search(PEOPLE.map((p) => ({ ...p, name: LONG,
    roles: [...p.roles, 'regional_operations_supervisor_with_a_long_name'] }))) },
  forbidden: { 'org.people.search': fail(403, { title: 'Forbidden', detail: 'org.user:read' }) },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
