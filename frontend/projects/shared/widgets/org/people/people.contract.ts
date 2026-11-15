/**
 * Contract of org.people. Hand-written: the generator does not read generic pages.
 */
import { s, type Infer } from '../../core/shape';

/** One person with their roles (AdminConsoleController.PersonRow). */
export const PERSON_ROW = s.object('PersonRow', {
  id: s.nullable(s.string()),
  name: s.nullable(s.string()),
  username: s.nullable(s.string()),
  email: s.nullable(s.string()),
  phone: s.nullable(s.string()),
  orgRole: s.nullable(s.string()),
  status: s.nullable(s.string()),
  roles: s.array(s.string()),
  pendingRoles: s.array(s.string()),
});

/**
 * GET /api/orgs/{id}/people → ListQuery.Page<PersonRow> (AdminConsoleController#people, org.user:read).
 * Query: q (name, username, email, phone, roles), sort=name|username|orgRole|status|roles|pendingRoles,
 * orgRole, status, roles, pendingRoles (comma = any of), page, size.
 */
export const ORG_PEOPLE_SEARCH = s.object('Page', {
  items: s.array(PERSON_ROW),
  total: s.integer(),
  page: s.integer(),
  size: s.integer(),
});
export type OrgPeopleSearch = Infer<typeof ORG_PEOPLE_SEARCH>;
export type Person = OrgPeopleSearch['items'][number];
