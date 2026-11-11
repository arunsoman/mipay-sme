import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AdminAuthService } from './auth';

/**
 * Session guard: requires a token + hydrated /api/me; non-admin accounts
 * (STAFF, unbound users) are bounced back to the IdP login screen.
 */
export const adminGuard: CanActivateFn = async () => {
  const auth = inject(AdminAuthService);
  const router = inject(Router);
  if (!auth.token()) return router.createUrlTree(['/login']);
  if (!auth.me()) await auth.hydrate();
  if (!auth.me() || !auth.isAdmin()) return router.createUrlTree(['/login']);
  return true;
};
