import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { PortalAuthService } from './auth';

/** Requires a token, a hydrated /api/me and an ADMIN of this portal's org type. */
export const portalGuard: CanActivateFn = async () => {
  const auth = inject(PortalAuthService);
  const router = inject(Router);
  if (!auth.me() && !await auth.hydrate()) return router.createUrlTree(['/login']);
  if (!auth.eligible()) {
    return router.createUrlTree(['/no-access']);
  }
  return true;
};
