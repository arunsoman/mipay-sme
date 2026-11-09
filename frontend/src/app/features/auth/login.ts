import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { homeForRole } from '../../core/guards';
import { MpCard } from '../../design-system/atoms/mp-card';
import { MpButton } from '../../design-system/atoms/mp-button';
import { MpSpinner } from '../../design-system/atoms/mp-spinner';

/**
 * E01/E02 + login. One screen, two tabs: merchant OTP or staff password.
 * In sandbox the dev OTP (123456) is displayed as a SIMULATED hint.
 */
@Component({
  selector: 'mp-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MpCard, MpButton, MpSpinner, RouterLink],
  template: `
    <div class="wrap">
      <div class="head">
        <h1>MiPay <span class="dim">SME</span></h1>
        <div class="hintline">Merchant payments, B2B stock purchases and cash advances (NAD)</div>
      </div>
      <mp-card class="box">
        <form (ngSubmit)="go()" class="grid">
          <input class="in" name="phone" [(ngModel)]="phone" placeholder="Phone (e.g. 0811111111)" required />
          @switch (mode()) {
            @case ('otp') {
              <input class="in" name="otp" [(ngModel)]="otp" placeholder="OTP" required />
              @if (hint()) {<div class="hint amber">Dev OTP: {{ hint() }} <span class="sim">SIMULATED</span></div>}
            }
            @case ('password') {
              <input class="in" name="pw" [(ngModel)]="password" type="password" placeholder="Password" required />
              <div class="hint">Ops 0827777777/ops123 - Lender 0813333333/lender123 - Supplier 0852222222/supplier123 - Admin 0819999999/admin123</div>
            }
          }
          <div class="row">
            <button type="button" class="switch" (click)="toggleMode()">
              {{ mode() === 'otp' ? 'Staff login instead' : 'Merchant OTP login' }}
            </button>
            <button class="go" type="submit" [disabled]="busy()">{{ busy() ? 'Signing in...' : 'Sign in' }}</button>
          </div>
          @if (error()) {<div class="err">{{ error() }}</div>}
          @if (hint()) {<div class="hint mt">New here? <a routerLink="/onboarding/merchant">Merchant registration</a> · <a routerLink="/onboarding/supplier">Supplier registration</a></div>}
        </form>
      </mp-card>
    </div>
  `,
  styles: [`
    .wrap { max-width: 380px; margin: 8vh auto 0; padding: var(--sp-4); }
    .head { margin-bottom: var(--sp-4); }
    h1 { font-size: var(--fs-xl); }
    .grid { display: grid; gap: var(--sp-3); }
    .in { width: 100%; padding: 10px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; }
    .row { display: flex; justify-content: space-between; align-items: center; gap: var(--sp-3); }
    .switch { background: none; border: none; color: var(--mp-teal); cursor: pointer; font: inherit; font-size: var(--fs-sm); }
    .go { background: var(--mp-navy); color: #fff; border: none; border-radius: var(--radius-1); padding: 9px var(--sp-5); font-weight: 600; cursor: pointer; font: inherit; }
    .hint { font-size: var(--fs-xs); color: var(--mp-text-dim); }
    .amber { color: #92610a; font-weight: 700; }
    .sim { background: var(--mp-amber); border-radius: 999px; padding: 1px 6px; font-size: 10px; color: #3b2f00; }
    .err { color: #dc2626; font-size: var(--fs-sm); }
    .mt { margin-top: var(--sp-3); }
    a { color: var(--mp-teal); }
  `],
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly mode = signal<'otp' | 'password'>('otp');
  readonly busy = signal(false);
  readonly hint = signal('');
  readonly error = signal('');

  phone = "";
  otp = "";
  password = "";

  toggleMode() {
    this.mode.set(this.mode() === 'otp' ? 'password' : 'otp');
    this.error.set('');
  }

  async go() {
    this.error.set('');
    this.busy.set(true);
    try {
      if (this.mode() === 'otp') {
        if (!this.hint()) {
          const res = await this.auth.requestOtp(this.phone);
          this.hint.set(res.hint ?? '');
          this.busy.set(false);
          return;
        }
        const tv = await this.auth.verifyOtp(this.phone, this.otp);
        this.router.navigateByUrl(homeForRole(tv.role));
      } else {
        const tv = await this.auth.loginWithPassword(this.phone, this.password);
        this.router.navigateByUrl(homeForRole(tv.role));
      }
    } catch {
      this.error.set('Sign-in failed - check credentials / request a new OTP');
      this.hint.set('');
    } finally {
      this.busy.set(false);
    }
  }
}
