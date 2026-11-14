import { MpAlert } from '../../design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ThemeService } from '../../core/theme.service';
import { homeForRole } from '../../core/guards';
import { MpCard } from '../../design-system/atoms/mp-card';

/**
 * E01/E02 + login. One screen, two tabs: merchant OTP or staff password.
 * 
 * FAPI 2.0 browser flow:
 * - User enters phone → gateway sets __Host-mp_sess cookie + redirects to IdP
 * - User completes OTP or password login at IdP
 * - Gateway creates session, sets __Host-mp_sess, redirects back
 * - Portal checks /api/me (gateway validates session) → populates me signal
 * - No tokens stored in browser localStorage
 */
@Component({
  selector: 'mp-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, FormsModule, MpCard, RouterLink],
  template: `
    <div class="wrap">
      <div class="head">
        <div class="mp-u-between">
          <h1>MiPay <span class="dim">SME</span></h1>
          <button class="tswitch" (click)="theme.toggle()" [title]="theme.dark() ? 'Switch to light' : 'Switch to dark'">{{ theme.dark() ? '☾' : '☀' }}</button>
        </div>
        <div class="hintline">MiPay SME Portal</div>
      </div>
      <mp-card class="box">
        <form (ngSubmit)="go()" class="grid">
          <input class="in" name="phone" [(ngModel)]="phone" placeholder="Phone" required />
          @switch (mode()) {
            @case ('otp') {
              <input class="in" name="otp" [(ngModel)]="otp" placeholder="OTP" required />
              @if (showHint()) {<div class="hint amber">Dev OTP: {{ hint() }} <span class="sim">SIMULATED</span></div>}
            }
            @case ('password') {
              <input class="in" name="pw" [(ngModel)]="password" type="password" placeholder="Password" required />
              <div class="hint">Use staff credentials</div>
            }
          }
          <div class="row">
            <button type="button" class="switch" (click)="toggleMode()">
              {{ mode() === 'otp' ? 'Staff login instead' : 'Merchant OTP login' }}
            </button>
            <button class="go" type="submit" [disabled]="busy()">{{ busy() ? 'Signing in...' : 'Sign in' }}</button>
          </div>
          @if (error()) {<mp-alert kind="bad">{{ error() }}</mp-alert>}
          @if (showHint()) {<div class="hint mt">New here? <a routerLink="/onboarding/merchant">Merchant registration</a> · <a routerLink="/onboarding/supplier">Supplier registration</a></div>}
        </form>
      </mp-card>
    </div>
  `,
  styles: [`
    .wrap { max-width: 380px; margin: 8vh auto 0; padding: var(--sp-4); }
    .head { margin-bottom: var(--sp-4); }
    .tswitch { background: none; border: 1px solid var(--mp-border); color: var(--mp-text); border-radius: var(--radius-1); padding: 4px 8px; cursor: pointer; font: inherit; font-size: var(--fs-md); }
    h1 { font-size: var(--fs-xl); }
    .grid { display: grid; gap: var(--sp-3); }
    .in { width: 100%; padding: 10px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; }
    .row { display: flex; justify-content: space-between; align-items: center; gap: var(--sp-3); }
    .switch { background: none; border: none; color: var(--mp-accent-deep); cursor: pointer; font: inherit; font-size: var(--fs-sm); }
    .go { background: var(--mp-accent); color: var(--mp-accent-ink); border: none; border-radius: var(--radius-1); padding: 9px var(--sp-5); font-weight: 600; cursor: pointer; font: inherit; }
    .hint { font-size: var(--fs-xs); color: var(--mp-text-dim); }
    .amber { color: #92610a; font-weight: 700; }
    .sim { background: var(--mp-amber); border-radius: 999px; padding: 1px 6px; font-size: 10px; color: #3b2f00; }
    a { color: var(--mp-accent-deep); }
  `],
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly theme = inject(ThemeService);

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
        // FAPI 2.0: navigate the browser to GET /auth/login so the gateway can
        // PAR → redirect to IdP. The page will not return here; the IdP redirects
        // to /auth/callback, the gateway creates a session, then redirects to '/'.
        this.auth.startLogin('/');
        // startLogin() triggers a full-page navigation; execution stops here.
        return;
      } else {
        // Password / staff login: same FAPI 2.0 redirect flow.
        this.auth.startLogin('/');
        return;
      }
    } catch (e: any) {
      this.error.set(e?.error?.detail || e?.message || 'Sign-in failed');
    } finally {
      this.busy.set(false);
    }
  }

  /** Show the dev OTP hint only in sandbox mode */
  showHint(): boolean {
    // In a real deployment, this would check the environment
    // For now, always show if phone is set (for demo purposes)
    return this.phone.length > 0 && this.mode() === 'otp';
  }

  /** Get the dev OTP hint text */
  getHint(): string {
    return 'Dev OTP: 123456 <span class="sim">SIMULATED</span>';
  }
}