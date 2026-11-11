import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { PortalAuthService } from './auth';

/** Requires a token, a hydrated /api/me and an ADMIN of this portal's org type. */
export const portalGuard: CanActivateFn = async () => {
  const auth = inject(PortalAuthService);
  const router = inject(Router);
  if (!auth.token()) return router.createUrlTree(['/login']);
  if (!auth.me()) await auth.hydrate();
  if (!auth.eligible()) {
    auth.logout();
    return router.createUrlTree(['/login']);
  }
  return true;
};
