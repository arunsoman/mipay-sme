/**
 * Contracts of admin.platform-access (AdminController#access, #accessDetail, #revokeAccess, #disableLink).
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/admin/access → List<Dtos.AccessUserRow> (admin.access:read). */
export const ADMIN_ACCESS_LIST = s.array(s.object('AccessUserRow', {
  userId: s.nullable(s.string()),
  phone: s.nullable(s.string()),
  role: s.nullable(s.string()),
  status: s.nullable(s.string()),
  activeSessions: s.integer(),
}));
export type AdminAccessList = Infer<typeof ADMIN_ACCESS_LIST>;
export type AccessUser = AdminAccessList[number];

/** GET /api/admin/access/{userId} → Dtos.AccessDetail (admin.access:read): sign-in links and sessions. */
export const ADMIN_ACCESS_DETAIL = s.object('AccessDetail', {
  userId: s.nullable(s.string()),
  phone: s.nullable(s.string()),
  role: s.nullable(s.string()),
  status: s.nullable(s.string()),
  links: s.nullable(s.array(s.object('AccessLinkRow', {
    id: s.nullable(s.string()),
    provider: s.nullable(s.string()),
    subject: s.nullable(s.string()),
    status: s.nullable(s.string()),
    idpEnabled: s.boolean(),
    activatedAt: s.nullable(s.dateTime()),
    lastLoginAt: s.nullable(s.dateTime()),
  }))),
  sessions: s.nullable(s.array(s.object('AccessSessionRow', {
    id: s.nullable(s.string()),
    deviceFingerprint: s.nullable(s.string()),
    createdAt: s.nullable(s.dateTime()),
    expiresAt: s.nullable(s.dateTime()),
    live: s.boolean(),
  }))),
});
export type AdminAccessDetail = Infer<typeof ADMIN_ACCESS_DETAIL>;
export type AccessLink = NonNullable<AdminAccessDetail['links']>[number];
export type AccessSession = NonNullable<AdminAccessDetail['sessions']>[number];

/** POST /api/admin/access/{userId}/revoke → Dtos.AccessRevokeResult (admin.access:administer): ends every session. */
export const ADMIN_ACCESS_REVOKE = s.object('AccessRevokeResult', { revoked: s.integer(), incidentId: s.nullable(s.string()) });
export type AdminAccessRevoke = Infer<typeof ADMIN_ACCESS_REVOKE>;
/** POST /api/admin/access/links/{linkId}/disable → Dtos.AccessDisableResult (admin.access:administer). */
export const ADMIN_ACCESS_DISABLE = s.object('AccessDisableResult', { linkId: s.nullable(s.string()), linkStatus: s.nullable(s.string()) });
export type AdminAccessDisable = Infer<typeof ADMIN_ACCESS_DISABLE>;
/** Both commands read no body. */
export const NO_BODY = s.object('NoBody', {});
