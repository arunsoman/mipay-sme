import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AdminAuthService } from './auth';

/**
 * Session guard: requires a token + hydrated /api/me; non-admin accounts
 * (STAFF, unbound users) are bounced back to the IdP login screen.
 */
export const adminGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AdminAuthService);
  const router = inject(Router);
  const login = () => router.createUrlTree(['/login'], { queryParams: { returnTo: state.url } });
  if (!auth.me() && !await auth.hydrate()) return login();
  if (!auth.me() || !auth.isAdmin()) return login();
  return true;
};
