import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Session guard: requires a valid session cookie, else redirect to login.
 * 
 * FAPI 2.0 browser flow: authentication is handled by the gateway (BFF) via
 * the __Host-mp_sess HttpOnly cookie. The portal checks /api/me to verify
 * the session and get user info. A 401 from /api/me triggers login.
 */
export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  
  // If no me signal, try to hydrate from /api/me (gateway validates session cookie)
  if (!auth.me()) {
    await auth.hydrate();
  }
  
  // If still no me, redirect to login (no valid session)
  if (!auth.me()) {
    return router.createUrlTree(['/login']);
  }
  
  // Valid session - allow access
  return true;
};

/** Role guard factory: portal pages can only be reached by their role. */
export const roleGuard = (role: string): CanActivateFn => async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  
  // Ensure we have a valid session
  if (!auth.me()) {
    await auth.hydrate();
  }
  
  // Check role match
  if (auth.role() === role) {
    return true;
  }
  
  // Redirect to appropriate home for this role
  return router.createUrlTree([homeForRole(auth.role())]);
};

export function homeForRole(role: string): string {
  switch (role) {
    case 'MERCHANT': return '/merchant';
    case 'SUPPLIER': return '/supplier';
    case 'LENDER': return '/lender';
    case 'TPTS_OPS': return '/ops/approvals';
    case 'MINDLABS_ADMIN': return '/admin';
    default: return '/login';
  }
}