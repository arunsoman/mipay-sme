import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MpPerm } from '../../../../../shared/web-core/perm-directive';

interface Client { id: string; clientName: string; clientId: string; scopes: string; status: string; rateLimitTps?: number }

@Component({
  selector: 'mpa-api-clients',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MpPerm],
  template: `
    <header><h1>API clients</h1><p>Register and revoke integration clients.</p></header>
    @if (error(); as e) {<div class="error" role="alert">{{ e }}</div>}
    <form *mpPerm="'admin.api_client:create'" (ngSubmit)="create()" class="panel form">
      <label>Client name <input name="clientName" [(ngModel)]="name" required /></label>
      <label>Rate limit (TPS) <input name="rateLimit" type="number" min="1" [(ngModel)]="rateLimit" /></label>
      <button type="submit" [disabled]="busy() || !name.trim()">Create client</button>
    </form>
    <section class="panel">
      <h2>Clients</h2>
      @if (loading()) {<p>Loading…</p>}
      @for (client of clients(); track client.id) {
        <div class="row">
          <div><strong>{{ client.clientName }}</strong><p>{{ client.clientId }} · {{ client.status }} · {{ client.scopes }}</p></div>
          @if (client.status !== 'REVOKED') {
            <button *mpPerm="'admin.api_client:administer'" type="button" (click)="revoke(client)" [disabled]="busy()">Revoke</button>
          }
        </div>
      } @empty {<p>No clients registered.</p>}
    </section>
  `,
  styles: [`
    :host { display: grid; gap: 16px; } h1,h2 { margin: 0; } p { color: var(--a-dim); margin: 5px 0; }
    .panel { border: 1px solid var(--a-border); background: var(--a-panel); border-radius: 10px; padding: 18px; }
    .form { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; }
    label { display: grid; gap: 5px; } input { padding: 8px; } button { padding: 8px 12px; }
    .row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 0; border-top: 1px solid var(--a-border); }
    .error { border: 1px solid #d66; color: #d66; padding: 10px; border-radius: 8px; }
  `],
})
export class ApiClients implements OnInit {
  private readonly http = inject(HttpClient);
  readonly clients = signal<Client[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  name = '';
  rateLimit: number | null = null;
  ngOnInit() { void this.reload(); }
  async reload() {
    this.loading.set(true); this.error.set('');
    try { this.clients.set(await firstValueFrom(this.http.get<Client[]>('/api/admin/api-clients'))); }
    catch { this.error.set('Could not load API clients.'); }
    finally { this.loading.set(false); }
  }
  async create() {
    this.busy.set(true); this.error.set('');
    try {
      await firstValueFrom(this.http.post('/api/admin/api-clients', { clientName: this.name.trim(),
        rateLimitTps: this.rateLimit || undefined }));
      this.name = ''; this.rateLimit = null; await this.reload();
    } catch { this.error.set('Could not create the API client.'); }
    finally { this.busy.set(false); }
  }
  async revoke(client: Client) {
    if (!confirm(`Revoke ${client.clientName}?`)) return;
    this.busy.set(true); this.error.set('');
    try { await firstValueFrom(this.http.post(`/api/admin/api-clients/${encodeURIComponent(client.id)}/revoke`, {})); await this.reload(); }
    catch { this.error.set('Could not revoke the API client.'); }
    finally { this.busy.set(false); }
  }
}
