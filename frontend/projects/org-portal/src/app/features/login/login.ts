import { MpAlert } from '../../../../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PortalAuthService, PortalRefusedError } from '../../core/auth';
import { readProblem } from '../../core/api';
import { PORTAL } from '../../portal.config';

@Component({
  selector: 'mpo-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, FormsModule, RouterLink],
  template: `
    <div class="stage">
      <div class="head">
        <div class="brand">MiPay <span>{{ portal.label }}</span></div>
        <div class="sub">Onboard your network · manage your organization</div>
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
      </form>
      <div class="foot">Got an activation link? <a routerLink="/activate">Set your password</a></div>
    </div>
  `,
  styles: [`
    .stage { max-width: 360px; margin: 12vh auto 0; padding: 16px; }
    .head { margin-bottom: 18px; }
    .brand { font-size: 26px; font-weight: 700; }
    .brand span { color: var(--a-teal); }
    .sub { color: var(--a-dim); font-size: var(--fs-sm); margin-top: 2px; }
    .card { display: grid; gap: 12px; background: var(--a-panel); border: 1px solid var(--a-border);
            border-radius: var(--radius); padding: 18px; }
    label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .foot { color: var(--a-dim); font-size: var(--fs-sm); text-align: center; margin-top: 14px; }
  `],
})
export class Login {
  private readonly auth = inject(PortalAuthService);
  private readonly router = inject(Router);
  readonly portal = PORTAL;

  readonly busy = signal(false);
  readonly error = signal('');
  identifier = '';
  password = '';

  async go() {
    this.error.set('');
    this.busy.set(true);
    try {
      this.identifier = this.identifier.trim();
      await this.auth.login(this.identifier, this.password);
      await this.router.navigateByUrl('/onboarding');
    } catch (e) {
      if (e instanceof PortalRefusedError) {
        this.error.set(e.reason === 'WRONG_ORG_TYPE'
          ? `This portal is for ${PORTAL.label.toLowerCase()} organizations; your account belongs to ${e.orgType ?? 'no organization'}.`
          : e.reason === 'NOT_ADMIN'
            ? 'Signed in, but only organization ADMINs can use this portal.'
            : 'Signed in, but your profile could not be loaded.');
      } else {
        const p = readProblem(e);
        this.error.set(p.status === 0 || p.status >= 502
          ? 'Can’t reach the MiPay server — check the portal address and that the backend is running.'
          : p.status === 401 || p.status === 403
            ? `Login failed for “${this.identifier}” — unknown user, wrong password, or account not active. (If the browser autofilled the form, clear the fields before typing.)`
            : `${p.title}: ${p.detail}`);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
