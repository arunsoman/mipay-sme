import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { PortalAuthService } from '../../core/auth';
import { PORTAL } from '../../portal.config';

@Component({
  selector: 'mpo-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stage">
      <div class="head"><div class="brand">MiPay <span>{{ portal.label }}</span></div>
        <div class="sub">Sign in securely with MiPay Identity</div></div>
      <div class="card"><button class="primary" type="button" (click)="go()">Continue to sign in</button></div>
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
  readonly portal = PORTAL;
  go() { this.auth.login(); }
}
