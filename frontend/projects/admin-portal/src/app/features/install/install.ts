import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Router } from '@angular/router';
import { AdminAuthService } from '../../core/auth';
import { readProblem } from '../../core/api';

interface FirstAdminResult { userId: string; username: string; status: string; setupLink: string }

@Component({
  selector: 'mpa-install',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <section class="install">
      <h1>Create the first administrator</h1>
      <p>This temporary account can only create the first administrator. It expires after 72 hours and is retired when the administrator sets a password.</p>
      <form (ngSubmit)="create()">
        <label>Full name <input name="name" [(ngModel)]="name" required autocomplete="name" /></label>
        <label>Username <input name="username" [(ngModel)]="username" required autocomplete="off" /></label>
        <label>Phone <input name="phone" [(ngModel)]="phone" required autocomplete="tel" /></label>
        <label>Email <input name="email" [(ngModel)]="email" type="email" autocomplete="email" /></label>
        <button type="submit" [disabled]="busy()">{{ busy() ? 'Creating…' : 'Create administrator' }}</button>
      </form>
      @if (error()) { <p role="alert">{{ error() }}</p> }
      @if (result(); as r) {
        <div class="result">
          <p>Administrator {{ r.username }} is awaiting password setup. Share this private, one-time link with them:</p>
          <a [href]="r.setupLink">Open administrator setup link</a>
          <button type="button" (click)="copy(r.setupLink)">Copy link</button>
          <p>After setup, sign out and sign in as {{ r.username }}.</p>
        </div>
      }
      <button type="button" (click)="logout()">Sign out</button>
    </section>
  `,
  styles: [`
    .install { max-width: 600px; margin: 40px auto; padding: 24px; }
    form { display: grid; gap: 14px; margin: 24px 0; }
    label { display: grid; gap: 5px; }
    input { padding: 10px; }
    button { width: fit-content; padding: 10px 16px; cursor: pointer; }
    .result { padding: 16px; border: 1px solid var(--a-border); overflow-wrap: anywhere; }
  `],
})
export class Install {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  readonly auth = inject(AdminAuthService);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly result = signal<FirstAdminResult | null>(null);
  name = '';
  username = '';
  phone = '';
  email = '';

  async create() {
    this.error.set('');
    this.busy.set(true);
    try {
      this.result.set(await firstValueFrom(this.http.post<FirstAdminResult>('/api/admin/bootstrap/first-admin', {
        name: this.name.trim(), username: this.username.trim(), phone: this.phone.trim(), email: this.email.trim(),
      })));
    } catch (e) {
      const p = readProblem(e);
      this.error.set(p.detail || p.title || 'Could not create the administrator');
    } finally { this.busy.set(false); }
  }

  async copy(link: string) { await navigator.clipboard.writeText(link); }
  async logout() { this.auth.logout(); await this.router.navigateByUrl('/login'); }
}
