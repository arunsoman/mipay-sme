import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AccessService } from '../../../shared/web-core/access';
import { BrowserSessionService } from '../../../shared/web-core/session';

/** One entry of api-services.json, as the dev proxy announces it (public/wb-service.json). */
export interface ApiService {
  id: string;
  label: string;
  portal: string;
  portalHost: string;
  gateway: string;
  services?: { id: string; label: string }[];
}

type Status = 'fixtures-only' | 'wrong-host' | 'unreachable' | 'signed-out' | 'signing-in' | 'connected';

/**
 * The harness's connection to a real MiPay API through the gateway (BFF). The developer picks a
 * service in api-services.json, opens the harness on that service's portal host and signs in
 * once; from then on every widget in "live" mode talks to the real API. FAPI 2.0 is the
 * gateway's job (PAR, PKCE, DPoP-bound tokens, refresh) - the harness and the widgets only see
 * a session cookie, a CSRF value and same-origin /api.
 */
@Injectable({ providedIn: 'root' })
export class Connection {
  private readonly http = inject(HttpClient);
  private readonly session = inject(BrowserSessionService);
  readonly access = inject(AccessService);

  readonly service = signal<ApiService | null>(null);
  readonly status = signal<Status>('fixtures-only');
  readonly detail = signal('');
  /** Widgets in live mode use the signed-in user's real permissions and org. */
  readonly useRealAccess = signal(true);

  readonly connected = computed(() => this.status() === 'connected');
  readonly who = computed(() => this.session.current()?.sub ?? '');
  readonly orgId = computed(() => (this.connected() ? (this.accessOrg() ?? null) : null));
  readonly cells = computed(() => [...this.access.cells()]);
  private readonly accessOrg = signal<string | null>(null);

  async start() {
    let svc: ApiService;
    try {
      svc = await firstValueFrom(this.http.get<ApiService>('wb-service.json'));
    } catch {
      this.set('fixtures-only', 'Fixtures only. To use a real API: pick a service in projects/widget-workbench/api-services.json and run WB_SERVICE=<id> npm run workbench:live.');
      return;
    }
    this.service.set(svc);
    if (location.hostname !== svc.portalHost) {
      this.set('wrong-host', `Open https://${svc.portalHost}:${location.port || '4300'}${location.pathname}${location.search} - the gateway binds sessions to the portal host`
        + ` (add "127.0.0.1 ${svc.portalHost}" to /etc/hosts once).`);
      return;
    }
    await this.check();
  }

  /** Re-read the session and, when signed in, the user's access. */
  async check(): Promise<boolean> {
    try {
      await firstValueFrom(this.http.get('/auth/session'));
    } catch (e) {
      const st = e instanceof HttpErrorResponse ? e.status : 0;
      if (st === 401 || st === 403) { this.set('signed-out', 'Not signed in to the gateway.'); return false; }
      this.set('unreachable', `The gateway at ${this.service()?.gateway} did not answer (HTTP ${st || 'no response'}). Is the stack up?`);
      return false;
    }
    await this.session.load(true);
    this.access.clear();
    await this.access.load(true);
    try {
      const me = await firstValueFrom(this.http.get<{ orgId: string | null }>('/api/me'));
      this.accessOrg.set(me.orgId);
    } catch { this.accessOrg.set(null); }
    this.set('connected', '');
    return true;
  }

  /** Sign in through the gateway in a popup; the harness keeps its state. */
  signIn() {
    const popup = window.open('/auth/login?return_to=/', 'mipay-login', 'width=520,height=760');
    this.set('signing-in', 'Finish signing in in the popup window…');
    const until = Date.now() + 5 * 60_000;
    const poll = async () => {
      if (await this.check().catch(() => false)) { try { popup?.close(); } catch { /* other origin */ } return; }
      if (Date.now() > until || popup?.closed) { if (this.status() === 'signing-in') this.set('signed-out', 'Sign-in was not completed.'); return; }
      setTimeout(poll, 2000);
    };
    setTimeout(poll, 2000);
  }

  signOut() { this.session.logout(); }

  private set(s: Status, d: string) { this.status.set(s); this.detail.set(d); }
}

/** The bar at the top of the harness: which API, signed in or not, and what to do about it. */
@Component({
  selector: 'wb-connection',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-connection]': 'c.status()' },
  template: `
    <span class="dot" [class.on]="c.connected()"></span>
    @if (c.service(); as s) {
      <b>{{ s.label }}</b>
      <span class="mono dim small">{{ s.portalHost }} → {{ s.gateway }}</span>
    } @else {
      <b>Fixtures</b>
    }
    <span class="grow"></span>
    @switch (c.status()) {
      @case ('connected') {
        <span class="small">signed in as <b>{{ c.who() }}</b> · org <span class="mono">{{ c.orgId() ?? '—' }}</span> ·
          {{ c.cells().length }} cells</span>
        <label class="chk small"><input type="checkbox" [checked]="c.useRealAccess()" (change)="c.useRealAccess.set(!c.useRealAccess())" />
          use my real permissions and org</label>
        <button type="button" class="lnk" (click)="c.check()">Refresh</button>
        <button type="button" class="lnk" (click)="c.signOut()">Sign out</button>
      }
      @case ('signed-out') {
        <span class="small warn">{{ c.detail() }}</span>
        <button type="button" (click)="c.signIn()">Sign in</button>
      }
      @case ('signing-in') { <span class="small warn">{{ c.detail() }}</span> }
      @default { <span class="small" [class.bad]="c.status() !== 'fixtures-only'" [class.dim]="c.status() === 'fixtures-only'">{{ c.detail() }}</span> }
    }
  `,
})
export class ConnectionBar {
  readonly c = inject(Connection);
  constructor() { void this.c.start(); }
}
