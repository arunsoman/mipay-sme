import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { RouterLink, RouterOutlet, NavigationEnd } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs/operators';
import { AuthService } from '../../core/auth.service';
import { SimulatedBanner } from '../molecules/simulated-banner';
import { EnvironmentService } from '../../core/env.service';

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
        @for (item of nav(); track item.label) {
          <a [routerLink]="item.link" class="item"
             [class.on]="current().split('?')[0].startsWith(item.link)">
            <span>{{ item.icon }}</span><span>{{ item.label }}</span>
          </a>
        }
        <div class="foot dim">{{ env().environment }} - {{ me()?.role }}</div>
      </aside>
      <div class="main">
        <header class="top">
          <div class="env"><simulated-banner /></div>
          <div class="user">
            <span class="dim">{{ me()?.name }}</span>
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
    .brand { display: flex; align-items: baseline; gap: 6px; padding: 0 var(--sp-2) var(--sp-3); }
    .logo { font-weight: 800; font-size: var(--fs-lg); color: #fff; }
    .item {
      display: flex; gap: 10px; align-items: center; padding: 8px var(--sp-2);
      border-radius: var(--radius-1); color: #cbd5e1; font-size: var(--fs-md);
    }
    .item:hover { background: rgb(255 255 255 / 0.08); }
    .item.on { background: rgb(255 255 255 / 0.14); color: #fff; font-weight: 600; }
    .foot { margin-top: auto; padding: var(--sp-2); }
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

  // role-aware nav config (single config: role gates which groups appear)
  readonly nav = computed(() => {
    const r = this.auth.role();
    const groups: { label: string; icon: string; link: string; roles: string[] }[] = [
      // merchant
      { label: 'Dashboard', icon: '◫', link: '/merchant', roles: ['MERCHANT'] },
      { label: 'Accept payment', icon: '＋', link: '/merchant/accept', roles: ['MERCHANT'] },
      { label: 'Payments', icon: '≡', link: '/merchant/payments', roles: ['MERCHANT'] },
      { label: 'Stock & orders', icon: '⇄', link: '/merchant/orders', roles: ['MERCHANT'] },
      { label: 'Inventory', icon: '▥', link: '/merchant/inventory', roles: ['MERCHANT'] },
      { label: 'Khata & tabs', icon: '✎', link: '/merchant/khata', roles: ['MERCHANT'] },
      { label: 'Cash advance', icon: '⇪', link: '/merchant/advance', roles: ['MERCHANT'] },
      { label: 'Balance & withdrawal', icon: 'N$', link: '/merchant/balance', roles: ['MERCHANT'] },
      { label: 'Statements', icon: '☰', link: '/merchant/statements', roles: ['MERCHANT'] },
      // supplier
      { label: 'Supplier home', icon: '⌂', link: '/supplier', roles: ['SUPPLIER'] },
      { label: 'Catalogue', icon: '▤', link: '/supplier/catalogue', roles: ['SUPPLIER'] },
      { label: 'Orders & invoices', icon: '⇄', link: '/supplier/orders', roles: ['SUPPLIER'] },
      { label: 'Receiving accounts', icon: '▣', link: '/supplier/accounts', roles: ['SUPPLIER'] },
      // lender
      { label: 'Consents & records', icon: '☰', link: '/lender', roles: ['LENDER'] },
      { label: 'Portfolio', icon: '◫', link: '/lender/portfolio', roles: ['LENDER'] },
      { label: 'Collections', icon: '⇩', link: '/lender/collections', roles: ['LENDER'] },
      // ops
      { label: 'Approvals', icon: '✓', link: '/ops/approvals', roles: ['TPTS_OPS'] },
      { label: 'Reconciliation', icon: '⚖', link: '/ops/recon', roles: ['TPTS_OPS'] },
      { label: 'Incidents', icon: '⚑', link: '/ops/incidents', roles: ['TPTS_OPS'] },
      { label: 'Service desk', icon: '☎', link: '/ops/desk', roles: ['TPTS_OPS'] },
      { label: 'Reports', icon: '▦', link: '/ops/reports', roles: ['TPTS_OPS'] },
      { label: 'Audit', icon: '☰', link: '/ops/audit', roles: ['TPTS_OPS'] },
      // admin
      { label: 'Overview', icon: '◫', link: '/admin', roles: ['MINDLABS_ADMIN'] },
      { label: 'Connectors', icon: '⑂', link: '/admin/connectors', roles: ['MINDLABS_ADMIN'] },
      { label: 'Config', icon: '⚙', link: '/admin/config', roles: ['MINDLABS_ADMIN'] },
      { label: 'Simulator', icon: '⌁', link: '/admin/simulator', roles: ['MINDLABS_ADMIN'] },
      { label: 'Access', icon: '⚿', link: '/admin/access', roles: ['MINDLABS_ADMIN'] },
      { label: 'Compliance', icon: '⛨', link: '/admin/compliance', roles: ['MINDLABS_ADMIN'] },
      { label: 'Ops hygiene', icon: '◷', link: '/admin/hygiene', roles: ['MINDLABS_ADMIN'] },
    ];
    return groups.filter((g) => g.roles.includes(r));
  });

  logout() {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
