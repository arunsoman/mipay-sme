/**
 * Sign-in and sessions (users.sign-in): each person's sign-in credential and the three fixes an
 * administrator needs - send the set-up link again, force a full credential reset, sign them out
 * everywhere. GET /api/orgs/{id}/auth/users · POST …/users/{uid}/resend-link | reset-credential | revoke-sessions.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  NO_BODY, USERS_SIGN_IN_LIST, USERS_SIGN_IN_RESEND, USERS_SIGN_IN_RESET, USERS_SIGN_IN_REVOKE,
} from './sign-in.contract';

export interface UsersSignInConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<UsersSignInConfig>({
  id: 'users.sign-in',
  version: '1.0.0',
  label: 'Sign-in and sessions',
  description: 'Each person’s sign-in credential; send the set-up link again, reset the credential, or sign them out everywhere.',
  category: 'Users',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.user:read'],
  actions: [
    { id: 'resend-link', label: 'Send the set-up link again', cell: 'org.user:administer', command: 'users.sign-in.resend-link' },
    { id: 'reset', label: 'Reset the credential', cell: 'org.user:administer', command: 'users.sign-in.reset' },
    { id: 'sign-out', label: 'Sign out everywhere', cell: 'org.user:administer', command: 'users.sign-in.revoke-sessions' },
  ],
  sources: ['users.sign-in.list'],
  commands: ['users.sign-in.resend-link', 'users.sign-in.reset', 'users.sign-in.revoke-sessions'],
  operations: ['view', 'approve'],
  commandOperations: {
    'users.sign-in.resend-link': 'approve',
    'users.sign-in.reset': 'approve',
    'users.sign-in.revoke-sessions': 'approve',
  },
  contracts: {
    'users.sign-in.list': USERS_SIGN_IN_LIST,
    'users.sign-in.resend-link': USERS_SIGN_IN_RESEND,
    'users.sign-in.reset': USERS_SIGN_IN_RESET,
    'users.sign-in.revoke-sessions': USERS_SIGN_IN_REVOKE,
  },
  requests: {
    'users.sign-in.resend-link': NO_BODY,
    'users.sign-in.reset': NO_BODY,
    'users.sign-in.revoke-sessions': NO_BODY,
  },
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  publishes: ['userId'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./sign-in.component').then((m) => m.UsersSignInWidget),
});
