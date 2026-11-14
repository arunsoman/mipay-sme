import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AdminAuthService } from '../../core/auth';

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
  template: `
    <div class="stage">
      <div class="head"><div class="brand">MiPay <span>Admin</span></div>
        <div class="sub">Sign in securely with MiPay Identity</div></div>
      <div class="card"><button class="primary" type="button" (click)="go()">Continue to sign in</button></div>
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
  go() { this.auth.login(); }
}
