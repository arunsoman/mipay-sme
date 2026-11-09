import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Session guard: requires a token + hydrated /api/me, else redirect to login. */
export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.token()) {
    return router.createUrlTree(['/login']);
  }
  if (!auth.me()) await auth.hydrate();
  return auth.me() ? true : router.createUrlTree(['/login']);
};

/** Role guard factory: portal pages can only be reached by their role. */
export const roleGuard = (role: string): CanActivateFn => async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.me()) await auth.hydrate();
  return auth.role() === role
    ? true
    : router.createUrlTree([homeForRole(auth.role())]);
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
