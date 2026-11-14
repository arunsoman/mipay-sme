import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AccessService } from '../../../../../shared/web-core/access';

/** Production overview: only live access and health information, no demo identities. */
@Component({
  selector: 'mpa-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <header><h1>Overview</h1><p>Organization, access and platform status</p></header>
    <div class="cards">
      <section class="panel">
        <h2>Organization</h2>
        <strong>{{ access.orgName() || 'Loading…' }}</strong>
        <p>{{ access.orgType() || '' }}</p>
      </section>
      <section class="panel">
        <h2>Your access</h2>
        <strong>{{ access.roles().length }} active role{{ access.roles().length === 1 ? '' : 's' }}</strong>
        <p>{{ access.cells().size }} backend permissions</p>
      </section>
      @if (access.can('admin.platform_ops:read')) {
        <section class="panel">
          <h2>Platform health</h2>
          @if (health(); as h) {
            <strong>{{ h.database || 'Unknown' }}</strong>
            <p>{{ h.openCriticalIncidents ?? 0 }} open critical incidents</p>
          } @else {<p>Loading health…</p>}
        </section>
      }
    </div>
    <section class="panel">
      <h2>Continue work</h2>
      <div class="links">
        @if (access.can('org.onboarding', 'ops.onboarding_request')) {<a routerLink="/onboarding">Onboarding applications</a>}
        @if (access.can('kyc.application', 'kyc.task')) {<a routerLink="/kyc">KYC review</a>}
        @if (access.can('org.user:administer')) {<a routerLink="/users">People</a>}
        @if (access.can('rbac.role')) {<a routerLink="/rbac">Roles and permissions</a>}
        @if (access.can('ops.incident')) {<a routerLink="/ops/incidents">Incidents</a>}
      </div>
    </section>
  `,
  styles: [`
    :host { display: grid; gap: 18px; }
    h1 { margin: 0; } h2 { margin: 0 0 12px; font-size: 15px; }
    p { color: var(--a-dim); margin: 6px 0 0; }
    .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; }
    .panel { border: 1px solid var(--a-border); background: var(--a-panel); border-radius: 10px; padding: 18px; }
    strong { font-size: 22px; } .links { display: flex; gap: 10px; flex-wrap: wrap; }
    .links a { color: var(--a-teal); padding: 7px 11px; border: 1px solid var(--a-border); border-radius: 8px; text-decoration: none; }
  `],
})
export class Overview implements OnInit {
  readonly access = inject(AccessService);
  private readonly http = inject(HttpClient);
  readonly health = signal<{ database?: string; openCriticalIncidents?: number } | null>(null);
  async ngOnInit() {
    await this.access.load();
    if (this.access.can('admin.platform_ops:read')) {
      try { this.health.set(await firstValueFrom(this.http.get<{ database?: string; openCriticalIncidents?: number }>('/api/admin/health'))); }
      catch { this.health.set({ database: 'Unavailable' }); }
    }
  }
}
