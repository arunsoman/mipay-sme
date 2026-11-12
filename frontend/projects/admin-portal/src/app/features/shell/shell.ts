import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MyAccess, USER_ADMIN_CELL } from '../../../../../shared/users/user-management';
import { AdminAuthService } from '../../core/auth';
import { MeView } from '../../core/api';

/**
 * Portal shell ([layout]/C11): the navigation lives in an off-canvas side drawer at every
 * screen width, so pages (the RBAC matrix above all) get the full width. The hamburger in
 * the top bar opens it; it closes on navigation, backdrop tap or Escape.
 * The environment banner derives from /api/env; SIMULATED is never hidden.
 */
@Component({
  selector: 'mpa-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  host: { '(document:keydown.escape)': 'closeNav()' },
  template: `
    <div class="layout">
      <div class="backdrop" aria-hidden="true" [class.show]="navOpen()" (click)="navOpen.set(false)"></div>
      <aside class="side" [class.open]="navOpen()" aria-label="Main navigation">
        <div class="brand">MiPay <span>Admin</span></div>
        <nav>
          @if (can('org.onboarding')) {<a routerLink="/onboarding" routerLinkActive="active" (click)="closeNav()">Onboarding</a>}
          @if (can('kyc.application', 'kyc.task')) {<a routerLink="/kyc" routerLinkActive="active" (click)="closeNav()">Organizations</a>}
          @if (canManageUsers()) {<a routerLink="/users" routerLinkActive="active" (click)="closeNav()">User management</a>}
          @if (can('org.rights')) {<a routerLink="/permissions" routerLinkActive="active" (click)="closeNav()">Permissions</a>}
          @if (can('rbac.role')) {<a routerLink="/rbac" routerLinkActive="active" (click)="closeNav()">RBAC Matrix</a>}
        </nav>
        <div class="spacer"></div>
        <div class="navfoot">
          <div class="who">
            <div class="name">{{ who().name }}</div>
            <div class="roles">
              <span class="chip teal">{{ who().role }}</span>
              @if (who().orgType) {<span class="chip">{{ who().orgType }} · {{ who().orgRole }}</span>}
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
          <div class="tb-brand">MiPay <span>Admin</span></div>
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

    .side { position: fixed; left: 0; top: 0; width: 260px; z-index: 50;
            height: 100vh; display: flex; flex-direction: column; transform: translateX(-100%); box-shadow: 0 0 24px rgba(0,0,0,.35);
            gap: 20px; padding: 18px 14px; background: #0b0b0b;
            border-right: 1px solid var(--a-border); transition: transform .22s ease; }
    .brand { font-weight: 900; font-size: 22px; letter-spacing: -0.045em; white-space: nowrap; padding: 2px 10px 6px; color: var(--a-teal); }
    .brand span { color: var(--a-teal); font-weight: 700; font-size: 13px; letter-spacing: .12em; text-transform: uppercase; margin-left: 6px; }
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
    .side.open { transform: translateX(0); }
    .topbar { display: flex; align-items: center; gap: 12px; padding: 10px 16px;
              background: var(--a-panel); border-bottom: 1px solid var(--a-border); position: sticky; top: 0; z-index: 30; }
    .tb-brand { font-weight: 900; font-size: 19px; letter-spacing: -0.045em; color: var(--a-teal); }
    .tb-brand span { color: var(--a-teal); font-weight: 700; font-size: 12px; letter-spacing: .12em; text-transform: uppercase; margin-left: 5px; }
    .burger { display: grid; gap: 4px; width: 38px; height: 34px; padding: 8px 8px; align-content: center; }
    .burger span { display: block; height: 2px; background: var(--a-dim); border-radius: 2px; }
    .tb-page { color: var(--a-dim); font-size: 13px; }
    .content { padding: 22px; margin: 0 auto; }

    @media (max-width: 900px) {
      .content { padding: 14px; }
    }
  `],
})
export class Shell implements OnInit {
  private readonly auth = inject(AdminAuthService);
  private readonly router = inject(Router);

  readonly me = signal<MeView | null>(null);
  readonly navOpen = signal(false);

  closeNav() {
    this.navOpen.set(false);
  }

  private readonly http = inject(HttpClient);
  /** User management shows only to holders of org.user:administer (RBAC or extended RBAC). */
  readonly canManageUsers = signal(false);
  /** Cells the user holds in their own org: each menu shows only with its permission. */
  readonly held = signal<Set<string>>(new Set());
  /** Pages and the permissions that show them, in menu order. */
  private readonly pages: [string, string[]][] = [['/onboarding', ['org.onboarding']], ['/kyc', ['kyc.application', 'kyc.task']], ['/users', ['org.user:administer']], ['/permissions', ['org.rights']], ['/rbac', ['rbac.role']]];

  /** A user who may not use the page they landed on goes to the first page they may use. */
  private toAllowedPage() {
    const url = this.router.url.split('?')[0];
    const here = this.pages.find(([path]) => url.startsWith(path));
    if (here && this.can(...here[1])) return;
    const first = this.pages.find(([, rows]) => this.can(...rows));
    if (first) void this.router.navigateByUrl(first[0]);
  }

  can(...rows: string[]) {
    const h = this.held();
    return rows.some((r) => r.includes(':') ? h.has(r) : [...h].some((c) => c.startsWith(r + ':')));
  }

  async ngOnInit() {
    await this.auth.hydrate();
    try {
      const a = await firstValueFrom(this.http.get<MyAccess>('/api/orgs/me/access'));
      this.held.set(new Set(a.permissions.map((x) => x.id)));
      this.canManageUsers.set(a.permissions.some((x) => x.id === USER_ADMIN_CELL));
      this.toAllowedPage();
    } catch { this.canManageUsers.set(false); }
  }

  who() {
    const me = this.me() ?? this.auth.me();
    return {
      name: me?.name ?? '…',
      role: me?.role ?? '',
      orgType: me?.orgType ?? null,
      orgRole: me?.orgRole ?? null,
    };
  }

  simulated() {
    return (this.me() ?? this.auth.me())?.simulated ?? true;
  }

  async logout() {
    this.auth.logout();
    await this.router.navigateByUrl('/login');
  }
}
