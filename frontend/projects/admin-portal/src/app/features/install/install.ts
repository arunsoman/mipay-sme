import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Router } from '@angular/router';
import { AdminAuthService } from '../../core/auth';
import { readProblem } from '../../core/api';

interface FirstAdminResult { userId: string; username: string; status: string; setupLink: string; reviewerUserId: string; reviewerUsername: string; reviewerStatus: string; reviewerSetupLink: string }

@Component({
  selector: 'mpa-install',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <section class="install">
      <h1>Create administrator maker and checker</h1>
      <p>This temporary account can only create the initial administrator pair. It expires after 72 hours. Use separate people for the maker and checker.</p>
      <form (ngSubmit)="create()">
        <h2>Administrator (maker)</h2>
        <label>Full name <input name="name" [(ngModel)]="name" required autocomplete="name" /></label>
        <label>Username <input name="username" [(ngModel)]="username" required autocomplete="off" /></label>
        <label>Phone <input name="phone" [(ngModel)]="phone" required autocomplete="tel" /></label>
        <label>Email <input name="email" [(ngModel)]="email" type="email" autocomplete="email" /></label>
        <h2>Admin reviewer (checker)</h2>
        <label>Full name <input name="reviewerName" [(ngModel)]="reviewerName" required autocomplete="name" /></label>
        <label>Username <input name="reviewerUsername" [(ngModel)]="reviewerUsername" required autocomplete="off" /></label>
        <label>Phone <input name="reviewerPhone" [(ngModel)]="reviewerPhone" required autocomplete="tel" /></label>
        <label>Email <input name="reviewerEmail" [(ngModel)]="reviewerEmail" type="email" autocomplete="email" /></label>
        <button type="submit" [disabled]="busy()">{{ busy() ? 'Creating…' : 'Create administrator pair' }}</button>
      </form>
      @if (error()) { <p role="alert">{{ error() }}</p> }
      @if (result(); as r) {
        <div class="result">
          <p>Maker {{ r.username }} is awaiting password setup. Share this private, one-time link with them:</p>
          <a [href]="r.setupLink">Open maker setup link</a>
          <button type="button" (click)="copy(r.setupLink)">Copy maker link</button>
          <p>Checker {{ r.reviewerUsername }} is awaiting password setup. Share their separate one-time link privately:</p>
          <a [href]="r.reviewerSetupLink">Open checker setup link</a>
          <button type="button" (click)="copy(r.reviewerSetupLink)">Copy checker link</button>
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
  reviewerName = '';
  reviewerUsername = '';
  reviewerPhone = '';
  reviewerEmail = '';

  async create() {
    this.error.set('');
    this.busy.set(true);
    try {
      this.result.set(await firstValueFrom(this.http.post<FirstAdminResult>('/api/admin/bootstrap/first-admin', {
        name: this.name.trim(), username: this.username.trim(), phone: this.phone.trim(), email: this.email.trim(),
        reviewerName: this.reviewerName.trim(), reviewerUsername: this.reviewerUsername.trim(),
        reviewerPhone: this.reviewerPhone.trim(), reviewerEmail: this.reviewerEmail.trim(),
      })));
    } catch (e) {
      const p = readProblem(e);
      this.error.set(p.detail || p.title || 'Could not create the administrator');
    } finally { this.busy.set(false); }
  }

  async copy(link: string) { await navigator.clipboard.writeText(link); }
  logout() { this.auth.logout(); }
}
