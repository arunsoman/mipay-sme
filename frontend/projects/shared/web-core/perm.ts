import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AccessService } from './access';
import { BusinessPortal, navAllows } from './portal-permission-catalogue';

/**
 * A page lives inside the shell only while its row is held. `permGuard` takes one row
 * or cell (or an OR list); users without any of them land on the shell's /no-access
 * page (the shared empty shell), never an error screen. With `menu`, the route also needs
 * a switched-on submenu once the user's roles pick submenus by menu cells (navAllows).
 */
export const permGuard =
  (perm: Array<string | string[]>, menu?: { portal: BusinessPortal; link: string }): CanActivateFn =>
  () => {
    const access = inject(AccessService);
    const router = inject(Router);
    return access
      .load()
      .then(() => (access.can(...perm) && (!menu || navAllows(menu.portal, menu.link, (rows) => access.can(...rows)))
        ? true : router.parseUrl('/no-access')));
  };

/** Nav item with the permission rows that make it visible (OR across rows). */
export interface GatedNavItem {
  label: string;
  icon: string;
  link: string;
  /** rows (any verb) or exact cells ("row:verb") - OR semantics. */
  perm: string[];
}
