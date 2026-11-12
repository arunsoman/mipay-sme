import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { OrgAccess } from '../../../../../shared/rbac/org-access';
import { refusal } from '../../../../../shared/kyc/kyc-api';

interface OrgRow { id: string; type: string; name: string; status: string; effectiveStatus: string; depth: number; }
interface RbacMismatch { userId: string; username: string | null; status: string | null; coreRole: string | null;
  expectedRole: string | null; activeRbacRoles: string[]; reason: string; }
interface RbacReconciliation { orgId: string; lifecycle: string; users: number;
  activeCoreRoleAssignments: number; mismatches: number; rows: RbacMismatch[]; }

/**
 * MiPay staff: "Roles & access" for any organization (docs/plans/extended-rbac.md ER5):
 * its role ceiling (editable for every org below the platform root), its own roles,
 * and the roles its people hold.
 */
@Component({
  selector: 'mpa-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, OrgAccess],
  template: `
    <div class="head">
      <h2>Roles &amp; access</h2>
      <select name="org" [ngModel]="orgId()" (ngModelChange)="selectOrg($event)">
        @for (o of orgs(); track o.id) {
          <option [value]="o.id">{{ indent(o.depth) }}{{ o.name }} · {{ o.type }} · {{ o.effectiveStatus }}</option>
        }
      </select>
    </div>
    @if (error(); as e) {<div class="err">{{ e }}</div>}
    @if (orgId(); as id) {
      <mp-org-access [orgId]="id" [manage]="true" [canEditCeiling]="id !== rootId()" />
      @if (id !== rootId()) {
        <section class="reconciliation panel">
          <div class="reconciliation-head">
            <div><b>Legacy role reconciliation</b><div class="muted small">Compare core ADMIN/STAFF roles with active RBAC assignments in this organization.</div></div>
            <button class="ghost" (click)="refreshReconciliation(id)" [disabled]="reconLoading()">Refresh</button>
          </div>
          @if (reconLoading()) {<div class="muted small">Checking…</div>}
          @if (reconError(); as e) {<div class="err">{{ e }}</div>}
          @if (reconciliation(); as report) {
            <div class="small">{{ report.mismatches }} mismatch(es) across {{ report.users }} core users and {{ report.activeCoreRoleAssignments }} active RBAC assignments.</div>
            @if (report.rows.length) {
              <div class="reconciliation-table"><table class="grid">
                <thead><tr><th>User</th><th>Core role</th><th>Expected RBAC</th><th>Active RBAC</th><th>Issue</th></tr></thead>
                <tbody>
                  @for (row of report.rows; track row.userId) {
                    <tr><td>{{ row.username || row.userId }}</td><td>{{ row.coreRole || 'none' }}</td>
                      <td>{{ row.expectedRole || 'none' }}</td><td>{{ row.activeRbacRoles.join(', ') || 'none' }}</td><td>{{ row.reason }}</td></tr>
                  }
                </tbody>
              </table></div>
            }
          }
        </section>
      }
    }
  `,
  styles: [`
    .head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
    select { min-width: 280px; max-width: 100%; }
    .err { color: var(--a-red); font-size: var(--fs-sm); }
    .reconciliation { margin-top: 14px; padding: 14px; }
    .reconciliation-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
    .reconciliation-table { overflow-x: auto; margin-top: 10px; }
    .small { font-size: var(--fs-xs); }
  `],
})
export class Access implements OnInit {
  private readonly http = inject(HttpClient);
  readonly orgs = signal<OrgRow[]>([]);
  readonly orgId = signal<string | null>(null);
  readonly rootId = signal<string | null>(null);
  readonly error = signal('');
  readonly reconciliation = signal<RbacReconciliation | null>(null);
  readonly reconError = signal('');
  readonly reconLoading = signal(false);
  readonly indent = (d: number) => '  '.repeat(Math.max(0, d));

  selectOrg(id: string) {
    this.orgId.set(id);
    this.reconciliation.set(null);
    this.reconError.set('');
    void this.refreshReconciliation(id);
  }

  async refreshReconciliation(id: string) {
    if (id === this.rootId()) return;
    this.reconLoading.set(true);
    this.reconError.set('');
    try {
      const report = await firstValueFrom(this.http.get<RbacReconciliation>(`/api/admin/rbac/reconciliation/${encodeURIComponent(id)}`));
      if (this.orgId() === id) this.reconciliation.set(report);
    } catch (e) {
      if (this.orgId() === id) this.reconError.set(refusal(e));
    } finally {
      if (this.orgId() === id) this.reconLoading.set(false);
    }
  }

  async ngOnInit() {
    try {
      const me = await firstValueFrom(this.http.get<{ id: string; type: string; name: string; effectiveStatus?: string }>('/api/orgs/me'));
      const page = await firstValueFrom(this.http.get<{ content: OrgRow[] }>('/api/orgs/me/descendants?size=500'));
      this.rootId.set(me.id);
      this.orgs.set([{ id: me.id, type: me.type, name: me.name, status: '', effectiveStatus: me.effectiveStatus ?? 'ACTIVE', depth: 0 },
        ...page.content.filter((o) => o.id !== me.id)]);
      this.orgId.set(this.orgs().length > 1 ? this.orgs()[1].id : me.id);
      void this.refreshReconciliation(this.orgId()!);
    } catch (e) {
      this.error.set(refusal(e));
    }
  }
}
