import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

interface OrganizationRow { id: string; name: string; type: string; parentId: string | null;
  status: string; country: string | null; registrationNumber: string | null }
interface Page<T> { content: T[]; totalElements: number; totalPages: number }

@Component({
  selector: 'mpa-org-directory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink],
  template: `
    <header><h1>Organizations</h1><p>Platform-wide directory and parent relationships</p></header>
    <div class="controls">
      <label>Type <select name="type" [(ngModel)]="type" (ngModelChange)="reload(0)">
        <option value="">All types</option>
        @for (t of types; track t) {<option [value]="t">{{ t }}</option>}
      </select></label>
      <label>Status <select name="status" [(ngModel)]="status" (ngModelChange)="reload(0)">
        <option value="">All statuses</option>
        @for (s of statuses; track s) {<option [value]="s">{{ s }}</option>}
      </select></label>
      <button type="button" (click)="reload(page())">Refresh</button>
    </div>
    @if (error(); as e) {<p role="alert" class="error">{{ e }}</p>}
    <section class="panel">
      @if (loading()) {<p>Loading…</p>}
      @for (org of rows(); track org.id) {
        <article>
          <div><strong>{{ org.name }}</strong><p>{{ org.type }} · {{ org.status }} · {{ org.country || 'Country not set' }}</p></div>
          <div class="parent">Parent: {{ org.parentId || 'Root' }}</div>
          <a [routerLink]="['/kyc']" [queryParams]="{org: org.id}">KYC</a>
        </article>
      } @empty {<p>No organizations match these filters.</p>}
    </section>
    <footer>
      <span>{{ total() }} organizations</span>
      <button type="button" (click)="reload(page() - 1)" [disabled]="page() === 0 || loading()">Previous</button>
      <span>Page {{ page() + 1 }}</span>
      <button type="button" (click)="reload(page() + 1)" [disabled]="page() + 1 >= totalPages() || loading()">Next</button>
    </footer>
  `,
  styles: [`
    :host { display: grid; gap: 14px; } h1 { margin: 0; } p { margin: 5px 0; color: var(--a-dim); }
    .controls, footer { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; }
    label { display: grid; gap: 5px; } select,button { padding: 8px; }
    .panel { border: 1px solid var(--a-border); border-radius: 10px; padding: 0 18px; background: var(--a-panel); }
    article { display: flex; align-items: center; gap: 20px; padding: 14px 0; border-bottom: 1px solid var(--a-border); }
    article>div:first-child { flex: 1; } .parent { color: var(--a-dim); font-size: 12px; }
    a { color: var(--a-teal); } .error { color: #d66; }
  `],
})
export class OrgDirectory implements OnInit {
  private readonly http = inject(HttpClient);
  readonly types = ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER', 'MERCHANT', 'LENDER'];
  readonly statuses = ['PENDING', 'ACTIVE', 'REJECTED', 'BLOCKED'];
  readonly rows = signal<OrganizationRow[]>([]);
  readonly page = signal(0);
  readonly total = signal(0);
  readonly totalPages = signal(0);
  readonly loading = signal(false);
  readonly error = signal('');
  type = '';
  status = '';
  ngOnInit() { void this.reload(0); }
  async reload(page: number) {
    if (page < 0) return;
    this.loading.set(true); this.error.set('');
    const q = new URLSearchParams({ page: String(page), size: '50' });
    if (this.type) q.set('type', this.type);
    if (this.status) q.set('status', this.status);
    try {
      const result = await firstValueFrom(this.http.get<Page<OrganizationRow>>(`/api/admin/organizations?${q}`));
      this.rows.set(result.content); this.page.set(page);
      this.total.set(result.totalElements); this.totalPages.set(result.totalPages);
    } catch { this.error.set('Could not load organizations.'); }
    finally { this.loading.set(false); }
  }
}
