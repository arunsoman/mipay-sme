import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AccessService } from './access';

/**
 * A page lives inside the shell only while its row is held. `permGuard` takes one row
 * or cell (or an OR list); users without any of them land on the shell's /no-access
 * page (the shared empty shell), never an error screen.
 */
export const permGuard =
  (perm: Array<string | string[]>): CanActivateFn =>
  () => {
    const access = inject(AccessService);
    const router = inject(Router);
    return access
      .load()
      .then(() => (access.can(...perm) ? true : router.parseUrl('/no-access')));
  };

/** Nav item with the permission rows that make it visible (OR across rows). */
export interface GatedNavItem {
  label: string;
  icon: string;
  link: string;
  /** rows (any verb) or exact cells ("row:verb") - OR semantics. */
  perm: string[];
}
