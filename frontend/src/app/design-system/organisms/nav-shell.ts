import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { RouterLink, RouterOutlet, NavigationEnd } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs/operators';
import { AuthService } from '../../core/auth.service';
import { SimulatedBanner } from '../molecules/simulated-banner';
import { EnvironmentService } from '../../core/env.service';
import { ThemeService } from '../../core/theme.service';
import { AccessService } from '../../../../projects/shared/web-core/access';
import { MenuNavEntry, linkFor, menuFor, navEntries } from '../../../../projects/shared/web-core/menu';

/**
 * Organism: application shell - sidebar nav (role-aware), topbar (user +
 * logout) and the global SIMULATED banner. One shell serves all 5 portals;
 * nav items differ only by role (no per-portal shells).
 */
@Component({
  selector: 'nav-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, SimulatedBanner],
  template: `
    <div class="shell">
      <aside class="side">
        <div class="brand">
          <span class="logo">MiPay</span><span class="dim">SME</span>
        </div>
        <nav class="links">
          @for (entry of nav(); track $index) {
            @if (entry.kind === 'group') {
              <div class="navgroup">{{ entry.label }}</div>
            } @else {
              <a [routerLink]="entry.link" class="item"
                 [class.on]="current().split('?')[0].startsWith(entry.link)">
                <span>{{ entry.icon }}</span><span>{{ entry.label }}</span>
              </a>
            }
          }
        </nav>
        <div class="foot dim">{{ env().environment }} - {{ me()?.role }}</div>
      </aside>
      <div class="main">
        <header class="top">
          <div class="env"><simulated-banner /></div>
          <div class="user">
            <span class="dim">{{ me()?.name }}</span>
            <button class="out theme" (click)="theme.toggle()" [title]="theme.dark() ? 'Switch to light theme' : 'Switch to dark theme'">{{ theme.dark() ? '☾ Dark' : '☀ Light' }}</button>
            <button class="out" (click)="logout()">Logout</button>
          </div>
        </header>
        <div class="page"><router-outlet /></div>
      </div>
    </div>
  `,
  styles: [`
    .shell { display: grid; grid-template-columns: var(--sidebar-w) 1fr; min-height: 100vh; }
    .side {
      background: var(--mp-navy); color: var(--mp-text-invert);
      display: flex; flex-direction: column; padding: var(--sp-4) var(--sp-2); gap: 2px;
      position: sticky; top: 0; height: 100vh;
    }
    /* the link list scrolls on its own (brand + footer stay put) with a slim themed bar */
    .links { flex: 1 1 auto; min-height: 0; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain;
             display: flex; flex-direction: column; gap: 2px; margin-right: -6px; padding-right: 4px;
             scrollbar-width: thin; scrollbar-color: rgb(255 255 255 / 0.16) transparent; }
    .links:hover { scrollbar-color: color-mix(in srgb, var(--mp-accent) 60%, transparent) transparent; }
    .links::-webkit-scrollbar { width: 6px; }
    .links::-webkit-scrollbar-track { background: transparent; margin: 4px 0; }
    .links::-webkit-scrollbar-thumb { background: rgb(255 255 255 / 0.16); border-radius: 999px; }
    .links:hover::-webkit-scrollbar-thumb { background: color-mix(in srgb, var(--mp-accent) 60%, transparent); }
    .links::-webkit-scrollbar-thumb:active { background: var(--mp-accent); }
    .brand { display: flex; align-items: baseline; gap: 6px; padding: 0 var(--sp-2) var(--sp-3); }
    .logo { font-weight: 800; font-size: var(--fs-lg); color: #fff; }
    .item {
      display: flex; gap: 10px; align-items: center; padding: 8px var(--sp-2);
      border-radius: var(--radius-1); color: #cbd5e1; font-size: var(--fs-md);
    }
    .item:hover { background: rgb(255 255 255 / 0.08); }
    .item.on { background: rgb(255 255 255 / 0.14); color: #fff; font-weight: 600; }
    .foot { padding: var(--sp-2); }
    .main { display: flex; flex-direction: column; min-width: 0; }
    .top {
      height: var(--topbar-h); display: flex; align-items: center; justify-content: flex-end;
      gap: var(--sp-4); padding: 0 var(--sp-5); background: var(--mp-surface);
      border-bottom: 1px solid var(--mp-border);
    }
    .user { display: flex; gap: var(--sp-3); align-items: center; }
    .out { border: 1px solid var(--mp-border); background: transparent; border-radius: var(--radius-1);
           padding: 6px 10px; cursor: pointer; font: inherit; font-size: var(--fs-sm); }
    .page { padding: var(--sp-5); max-width: 1200px; width: 100%; margin: 0 auto; }
  `],
})
export class NavShell {
  private readonly auth = inject(AuthService);
  private readonly envSvc = inject(EnvironmentService);
  private readonly router = inject(Router);
  readonly theme = inject(ThemeService);
  readonly access = inject(AccessService);

  constructor() {
    // rights drive the nav; load them as soon as the shell renders
    void this.access.load();
  }

  readonly me = () => this.auth.me();
  readonly env = () => this.envSvc.env() ?? { environment: '', simulated: true, banner: '' };

  private readonly routerEvents = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: '' },
  );

  readonly current = () => this.routerEvents() ?? '';

  // One menu registry (shared/web-core/menu.ts): the persona selects the group,
  // a HELD PERMISSION selects the item - there is no fallback for ungated items.
  // Labels and permission rows come from the registry, the same source the route
  // guards (access-map.ts) derive from, so nav and guards can never drift apart.
  readonly nav = computed<MenuNavEntry[]>(() => {
    const r = this.auth.role();
    return navEntries('sme', (item) => {
      if (item.roles && !item.roles.includes(r)) return false;
      return this.access.can(...item.perm);
    });
  });

  logout() {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
