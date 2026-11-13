import { MpAlert } from '../../../../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MessageView, readProblem } from '../../core/api';
import { PORTAL } from '../../portal.config';

/**
 * Activation: the new admin opens the link from their onboarding message
 * (…/activate?token=…) or pastes it, then chooses a password. The token is
 * single-use; every failure comes back as the same ACTIVATION_INVALID.
 */
@Component({
  selector: 'mpo-activate',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, FormsModule, RouterLink],
  template: `
    <div class="stage">
      <div class="head">
        <div class="brand">MiPay <span>{{ portal.label }}</span></div>
        <div class="sub">Activate your account</div>
      </div>
      @if (done(); as d) {
        <div class="card">
          <mp-alert kind="ok">Account activated. Your username is <b class="mono">{{ d }}</b>.</mp-alert>
          <a routerLink="/login">Go to sign in</a>
        </div>
      } @else {
        <form class="card" (ngSubmit)="go()">
          <label>Activation link or token
            <input name="token" [(ngModel)]="token" placeholder="paste the link from your message" required />
          </label>
          <label>New password (12+ characters, not your username or phone)
            <input name="pw" [(ngModel)]="password" type="password" autocomplete="new-password" required />
          </label>
          <label>Repeat password
            <input name="pw2" [(ngModel)]="password2" type="password" autocomplete="new-password" required />
          </label>
          <button class="primary" type="submit" [disabled]="busy()">Activate</button>
          @if (error()) {<mp-alert kind="bad">{{ error() }}</mp-alert>}
        </form>
        <div class="foot"><a routerLink="/login">Back to sign in</a></div>
      }
    </div>
  `,
  styles: [`
    .stage { max-width: 400px; margin: 12vh auto 0; padding: 16px; }
    .head { margin-bottom: 18px; }
    .brand { font-size: 26px; font-weight: 700; }
    .brand span { color: var(--a-teal); }
    .sub { color: var(--a-dim); font-size: var(--fs-sm); margin-top: 2px; }
    .card { display: grid; gap: 12px; background: var(--a-panel); border: 1px solid var(--a-border);
            border-radius: var(--radius); padding: 18px; }
    label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .foot { text-align: center; margin-top: 14px; font-size: var(--fs-sm); }
  `],
})
export class Activate implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly portal = PORTAL;

  readonly busy = signal(false);
  readonly error = signal('');
  readonly done = signal<string | null>(null);
  token = '';
  password = '';
  password2 = '';

  ngOnInit() {
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
  }

  private extractToken(): string {
    const raw = this.token.trim();
    const m = raw.match(/[?&]token=([^&\s]+)/);
    return m ? decodeURIComponent(m[1]) : raw;
  }

  async go() {
    this.error.set('');
    if (this.password !== this.password2) {
      this.error.set('Passwords do not match.');
      return;
    }
    this.busy.set(true);
    try {
      const res = await firstValueFrom(this.http.post<MessageView>('/api/auth/activate',
        { token: this.extractToken(), password: this.password }));
      this.done.set(res.label ?? '(see your message)');
    } catch (e) {
      const p = readProblem(e);
      this.error.set(p.code === 'ACTIVATION_INVALID'
        ? 'This link is invalid, expired or already used — or the password does not meet the policy. Ask your parent organization to resend it.'
        : `${p.title}: ${p.detail}`);
    } finally {
      this.busy.set(false);
    }
  }
}
