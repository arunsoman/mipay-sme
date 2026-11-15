/**
 * Response contracts: what the server must send. The port checks every answer against these and
 * the harness shows any mismatch.
 */
import { s, type Infer } from '../../core/shape';

/** One organization (AdminConsoleController.OrgRow). */
export const ORG_ROW = s.object('OrgRow', {
  id: s.nullable(s.string()),
  name: s.nullable(s.string()),
  type: s.nullable(s.string()),
  status: s.nullable(s.string()),
  kycStatus: s.nullable(s.string()),
  parentId: s.nullable(s.string()),
  parentName: s.nullable(s.string()),
  users: s.integer(),
  preset: s.nullable(s.string()),
  features: s.integer(),
  warnings: s.array(s.string()),
  createdAt: s.nullable(s.dateTime()),
});

/**
 * Hand-written: GET /api/admin/organizations/search → ListQuery.Page<OrgRow>
 * (AdminConsoleController#organizations, admin.org_directory:read). Query: q, sort=name|type|status|
 * kycStatus|parentName|users|preset|features|warnings|createdAt,asc|desc, type, status, kycStatus,
 * preset, warnings (comma = any of), page, size.
 */
export const ADMIN_ORGANIZATIONS_SEARCH = s.object('Page', {
  items: s.array(ORG_ROW),
  total: s.integer(),
  page: s.integer(),
  size: s.integer(),
});
export type AdminOrganizationsSearch = Infer<typeof ADMIN_ORGANIZATIONS_SEARCH>;
export type OrgRow = AdminOrganizationsSearch['items'][number];
