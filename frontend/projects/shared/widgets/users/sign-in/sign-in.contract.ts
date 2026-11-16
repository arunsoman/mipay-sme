/**
 * Contracts of users.sign-in (OrgAuthAccessController). Every answer is a Map on the server; the
 * keys below are the ones OrgAuthAccessController#view / #resendLink / #resetCredential /
 * #revokeSessions always put.
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/orgs/{id}/auth/users (org.user:read): the org's people and their sign-in credential state. */
export const USERS_SIGN_IN_LIST = s.array(s.object('CredentialUser', {
  id: s.string(),
  name: s.nullable(s.string()),
  username: s.nullable(s.string()),
  phone: s.nullable(s.string()),
  status: s.nullable(s.string()),
  org_role: s.nullable(s.string()),
  subject: s.nullable(s.string()),
  link_status: s.nullable(s.string()),
  idp_enabled: s.boolean(),
}));
export type UsersSignInList = Infer<typeof USERS_SIGN_IN_LIST>;
export type CredentialUser = UsersSignInList[number];

const ENQUEUED_WITH_SCOPE = s.object('CredentialCommand', { enqueued: s.boolean(), user_id: s.string(), scope: s.string() });

/** POST /api/orgs/{id}/auth/users/{uid}/resend-link (org.user:administer): {enqueued, user_id, scope: LINK}. */
export const USERS_SIGN_IN_RESEND = ENQUEUED_WITH_SCOPE;
/** POST /api/orgs/{id}/auth/users/{uid}/reset-credential (org.user:administer): {enqueued, user_id, scope: FULL}. */
export const USERS_SIGN_IN_RESET = ENQUEUED_WITH_SCOPE;
/** POST /api/orgs/{id}/auth/users/{uid}/revoke-sessions (org.user:administer): {enqueued, user_id}. */
export const USERS_SIGN_IN_REVOKE = s.object('SessionsCommand', { enqueued: s.boolean(), user_id: s.string() });
export type UsersSignInAnswer = { enqueued: boolean; user_id: string; scope?: string };

/** The three commands read no body. */
export const NO_BODY = s.object('NoBody', {});
