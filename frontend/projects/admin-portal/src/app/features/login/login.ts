import { MpAlert } from '../../../../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AdminAuthService, PortalRefusedError } from '../../core/auth';
import { readProblem } from '../../core/api';

/**
 * IdP-backed login: username + password posted to the platform identity
 * provider (POST /api/auth/login). The IdP validates credentials, issues the
 * signed JWT with orgId/orgType/orgRole claims ([H6/M9]); the portal keeps
 * only the bearer token. Accounts without ADMIN/platform authority are
 * refused with a clear message (the IdP accepted them - the portal did not).
 */
@Component({
  selector: 'mpa-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, FormsModule, RouterLink],
  template: `
    <div class="stage">
      <div class="head">
        <div class="brand">MiPay <span>Admin</span></div>
        <div class="sub">Sign in to manage your organizations</div>
      </div>
      <form class="card" (ngSubmit)="go()">
        <label>Username
          <input name="identifier" [(ngModel)]="identifier" placeholder="username (or phone)"
                 autocomplete="username" required autofocus />
        </label>
        <label>Password
          <input name="password" [(ngModel)]="password" type="password" placeholder="password"
                 autocomplete="current-password" required />
        </label>
        <button class="primary" type="submit" [disabled]="busy()">
          {{ busy() ? 'Signing in…' : 'Sign in' }}
        </button>
        @if (error()) {<mp-alert kind="bad">{{ error() }}</mp-alert>}
        @if (devHint()) {<div class="hint">
          Sandbox seed accounts —
          <button type="button" class="use" (click)="useSeed('ops-admin-ops', 'ops123')">use ops-admin-ops</button>
          <button type="button" class="use" (click)="useSeed('ops-admin-mindlabs', 'admin123')">use ops-admin-mindlabs</button>
          <span class="simtag">SIMULATED</span>
        </div>}
      </form>
      <div class="foot">Got a set-up link? <a routerLink="/activate">Set your password</a></div>
    </div>
  `,
  styles: [`
    .stage { max-width: 360px; margin: 12vh auto 0; padding: 16px; }
    .head { margin-bottom: 18px; }
    .brand { font-size: 26px; font-weight: 700; letter-spacing: .01em; }
    .brand span { color: var(--a-teal); }
    .sub { color: var(--a-dim); font-size: var(--fs-sm); margin-top: 2px; }
    .card { display: grid; gap: 12px; background: var(--a-panel); border: 1px solid var(--a-border);
            border-radius: var(--radius); padding: 18px; }
    label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .use { padding: 2px 10px; font-size: var(--fs-xs); margin: 4px 4px 0 0; font-family: ui-monospace, Menlo, monospace; }
    .simtag { background: var(--a-amber); color: #3b2f00; border-radius: 999px;
              padding: 1px 6px; font-size: 10px; margin-left: 4px; }
    .foot { color: var(--a-dim); font-size: var(--fs-xs); text-align: center; margin-top: 14px; }
  `],
})
export class Login {
  private readonly auth = inject(AdminAuthService);
  private readonly router = inject(Router);

  readonly busy = signal(false);
  readonly error = signal('');
  /** The sandbox seed accounts are only ever hinted on a local machine, never on a public host. */
  readonly devHint = signal(['localhost', '127.0.0.1'].includes(location.hostname)
    && localStorage.getItem('mp.admin.hint') !== 'off');

  identifier = '';
  password = '';

  async go() {
    this.error.set('');
    this.busy.set(true);
    this.identifier = this.identifier.trim();
    try {
      await this.auth.login(this.identifier, this.password);
      await this.router.navigateByUrl(this.auth.me()?.username === 'installer' ? '/install' : '/onboarding');
    } catch (e) {
      if (e instanceof PortalRefusedError) {
        this.error.set('Signed in, but this portal is restricted to ADMIN and platform staff accounts.');
      } else {
        const p = readProblem(e);
        this.error.set(p.status === 0 || p.status >= 502
          ? 'Can’t reach the MiPay server — check the portal address (the admin portal is http://localhost:4201) and that the backend is running.'
          : p.status === 401 || p.status === 403
            ? `Login failed for “${this.identifier}” — unknown user or wrong password. (If the browser autofilled the form, clear the fields before typing.)`
            : `${p.title}: ${p.detail}`);
      }
    } finally {
      this.busy.set(false);
    }
  }

  /** Sandbox only: replace (not append to) whatever the browser autofilled. */
  useSeed(identifier: string, password: string) {
    this.identifier = identifier;
    this.password = password;
    this.error.set('');
  }
}
