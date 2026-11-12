import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { PortalAuthService } from '../../core/auth';
import { OrgView, statusClass } from '../../core/api';
import { PORTAL } from '../../portal.config';
import { AccessService } from '../../../../../shared/web-core/access';

/**
 * Portal shell ([layout]/C11): a left navigation sidebar. On screens wider
 * than 901px it is permanently open; below that it collapses into an
 * off-canvas drawer that slides in/out via the hamburger button and closes on
 * navigation or by tapping the backdrop.
 */
@Component({
  selector: 'mpo-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="layout">
      <div class="backdrop" aria-hidden="true" [class.show]="navOpen()" (click)="navOpen.set(false)"></div>
      <aside class="side" [class.open]="navOpen()" aria-label="Main navigation">
        <div class="brand">MiPay <span>{{ portal.label }}</span></div>
        <nav>
          @if (can('org.onboarding')) {<a routerLink="/onboarding" routerLinkActive="active" (click)="closeNav()">Onboarding</a>}
          @if (can('org.tree')) {<a routerLink="/network" routerLinkActive="active" (click)="closeNav()">My network</a>}
          @if (can('org.user:administer')) {<a routerLink="/users" routerLinkActive="active" (click)="closeNav()">User management</a>}
          @if (can('kyc.application', 'kyc.task')) {<a routerLink="/kyc" routerLinkActive="active" (click)="closeNav()">Organizations</a>}
          @if (can('supplier.catalogue')) {<a routerLink="/supplier/catalogue" routerLinkActive="active" (click)="closeNav()">Catalogue</a>}
          @if (can('supplier.order', 'supplier.invoice')) {<a routerLink="/supplier/orders" routerLinkActive="active" (click)="closeNav()">Orders & invoices</a>}
          @if (can('supplier.bank_account')) {<a routerLink="/supplier/accounts" routerLinkActive="active" (click)="closeNav()">Receiving accounts</a>}
          @if (can('supplier.statement')) {<a routerLink="/supplier/statements" routerLinkActive="active" (click)="closeNav()">Statements</a>}
        </nav>
        <div class="spacer"></div>
        <div class="navfoot">
          <div class="who">
            <div class="name">{{ auth.name() }}</div>
            <div class="roles">
              @if (org(); as o) {
                <span class="chip teal">{{ o.name }}</span>
                <span class="chip {{ cls(o.effectiveStatus) }}">{{ o.effectiveStatus }}</span>
              }
              <span class="chip">{{ auth.me()?.orgRole }}</span>
            </div>
          </div>
          <button class="ghost wide" (click)="logout()">Sign out</button>
        </div>
      </aside>
      <main>
        <div class="topbar">
          <button class="burger ghost" (click)="navOpen.set(!navOpen())" aria-label="Toggle menu">
            <span></span><span></span><span></span>
          </button>
          <div class="tb-brand">MiPay <span>{{ portal.label }}</span></div>
        </div>
        <div class="content"><router-outlet /></div>
      </main>
    </div>
  `,
  styles: [`
    .layout { display: flex; min-height: calc(100vh - 34px); }
    .backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.45); opacity: 0; pointer-events: none;
                transition: opacity .2s ease; z-index: 40; }
    .backdrop.show { opacity: 1; pointer-events: auto; }

    .side { position: sticky; top: 0; align-self: flex-start; width: 232px; flex: none; z-index: 50;
            height: calc(100vh - 34px); display: flex; flex-direction: column;
            gap: 20px; padding: 18px 14px; background: #0b0b0b;
            border-right: 1px solid var(--a-border); transition: transform .22s ease; }
    .brand { font-weight: 900; font-size: 22px; letter-spacing: -0.045em; white-space: nowrap; padding: 2px 10px 6px; color: var(--a-teal); }
    .brand span { color: var(--portal-accent, var(--a-teal)); font-weight: 700; font-size: 13px; letter-spacing: .12em; text-transform: uppercase; margin-left: 6px; }
    .spacer { flex: 1; }
    .navfoot { display: grid; gap: 10px; border-top: 1px solid var(--a-border); padding-top: 12px; }
    .who { padding: 0 2px; }
    .name { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .roles { display: flex; gap: 5px; flex-wrap: wrap; margin-top: 6px; }
    button.wide { width: 100%; }

    nav { display: flex; flex-direction: column; gap: 4px; }
    nav a { color: var(--a-dim); text-decoration: none; padding: 10px 14px; border-radius: 999px; font-weight: 600;
            font-size: 13.5px; border: 1px solid transparent; white-space: nowrap; }
    nav a:hover { color: var(--a-text); background: var(--a-panel-2); }
    nav a.active { color: var(--a-accent-ink); background: var(--a-teal); border-color: var(--a-teal); font-weight: 800; }

    main { flex: 1; min-width: 0; }
    .topbar { display: none; align-items: center; gap: 12px; padding: 10px 16px;
              background: var(--a-panel); border-bottom: 1px solid var(--a-border); position: sticky; top: 0; z-index: 30; }
    .tb-brand { font-weight: 900; font-size: 19px; letter-spacing: -0.045em; color: var(--a-teal); }
    .tb-brand span { color: var(--portal-accent, var(--a-teal)); font-weight: 700; font-size: 12px; letter-spacing: .12em; text-transform: uppercase; margin-left: 5px; }
    .burger { display: grid; gap: 4px; width: 38px; height: 34px; padding: 8px 8px; align-content: center; }
    .burger span { display: block; height: 2px; background: var(--a-dim); border-radius: 2px; }
    .content { padding: 22px; max-width: 1500px; margin: 0 auto; }

    @media (max-width: 900px) {
      .topbar { display: flex; }
      .side { position: fixed; left: 0; top: 0; height: 100vh;
              transform: translateX(-100%); box-shadow: 0 0 24px rgba(0,0,0,.35); }
      .side.open { transform: translateX(0); }
      .content { padding: 14px; }
    }
  `],
})
export class Shell implements OnInit {
  readonly auth = inject(PortalAuthService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  readonly portal = PORTAL;
  readonly org = signal<OrgView | null>(null);
  readonly cls = statusClass;
  readonly navOpen = signal(false);

  closeNav() {
    this.navOpen.set(false);
  }

  /** Pages and the permissions that show them, in menu order. */
  private readonly pages: [string, string[]][] = [
    ['/onboarding', ['org.onboarding']],
    ['/network', ['org.tree']],
    ['/users', ['org.user:administer']],
    ['/kyc', ['kyc.application', 'kyc.task']],
    ['/supplier/catalogue', ['supplier.catalogue']],
    ['/supplier/orders', ['supplier.order', 'supplier.invoice']],
    ['/supplier/accounts', ['supplier.bank_account']],
    ['/supplier/statements', ['supplier.statement']],
  ];

  /** A user who may not use the page they landed on goes to the first page they may
   *  use; a user with no rights at all lands on the shared empty shell - and one who
   *  re-gains a right while sitting on it leaves it for the first allowed page. */
  private toAllowedPage() {
    const url = this.router.url.split('?')[0];
    const here = this.pages.find(([path]) => url.startsWith(path));
    if (this.access.empty()) { void this.router.navigateByUrl('/no-access'); return; }
    if (url === '/no-access') {
      const first = this.pages.find(([, rows]) => this.can(...rows));
      if (first) void this.router.navigateByUrl(first[0]);
      return;
    }
    if (here && this.can(...here[1])) return;
    const first = this.pages.find(([, rows]) => this.can(...rows));
    void this.router.navigateByUrl(first ? first[0] : '/no-access');
  }

  /** The one shared access engine; nav + pages key off the held RBAC cells. */
  readonly access = inject(AccessService);

  can(...rows: string[]) {
    return this.access.can(...rows);
  }

  async ngOnInit() {
    try { this.org.set(await firstValueFrom(this.http.get<OrgView>('/api/orgs/me'))); } catch { /* header only */ }
    await this.access.load();
    this.toAllowedPage();
    window.matchMedia('(min-width: 901px)').addEventListener('change', () => this.navOpen.set(false));
  }

  async logout() {
    this.auth.logout();
    await this.router.navigateByUrl('/login');
  }
}
