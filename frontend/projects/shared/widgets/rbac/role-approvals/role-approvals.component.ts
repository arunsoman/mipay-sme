import { ChangeDetectionStrategy, Component, computed, effect, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { roleName, when } from '../../core/text';
import { DataCol, DataTable } from '../../../../../src/app/design-system/organisms/data-table';
import { ApprovalActions } from '../../../../../src/app/design-system/organisms/approval-actions';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { RBAC_HINTS, roleLabel } from '../../../rbac/rbac-api';
import { refusal } from '../../../kyc/kyc-api';
import type { RbacRoleApprovalsConfig } from './role-approvals.widget';
import type { RbacApprovalsDecideRequest, RbacApprovalsList, RbacApprovalsMembers, RoleRequest } from './role-approvals.contract';

@Component({
  selector: 'mpw-rbac-role-approvals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, ApprovalActions, FormField, MpAlert, EmptyState],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="Role requests waiting there appear here." />
    } @else if (rows() && !rows()!.length) {
      <empty-state title="Nothing to approve" hint="Role requests that need a second person appear here." />
    } @else {
      <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" [selectable]="true"
                     placeholder="Search requests" loading="Loading requests…" (rowClick)="pick($event)" />
    }
    @if (selected(); as r) {
      <section class="decide" data-selected-request>
        <div><b>{{ name(r.subject) }}</b> asks for <b>{{ role(r.role) }}</b>
          <span class="dim">(asked by {{ name(r.requested_by) }}, {{ day(r.requested_at) }})</span></div>
        <mp-form-field key="reason" label="Reason" [value]="reason()" (changed)="reason.set($event)" />
        <approval-actions data-action="decide" perm="rbac.assignment:authorize" [status]="r.status"
                          [note]="reason()" (decide)="decide(r, $event.approve)" />
      </section>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .decide { display: grid; gap: var(--sp-2, 8px); padding-top: var(--sp-3, 12px); border-top: 1px solid var(--a-border, #2b2b2b); overflow-wrap: anywhere; }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); }
  `],
})
export class RbacRoleApprovalsWidget extends MiPayWidget<RbacRoleApprovalsConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly rows = signal<RbacApprovalsList | null>(null);
  readonly members = signal<RbacApprovalsMembers>([]);
  readonly selected = signal<RoleRequest | null>(null);
  readonly reason = signal('');
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly day = (iso: string | null) => when(iso);

  private readonly allCols: DataCol<RoleRequest>[] = [
    { key: 'person', label: 'Person', text: (r) => this.name(r.subject) },
    { key: 'role', label: 'Role', text: (r) => this.role(r.role) },
    { key: 'by', label: 'Asked by', text: (r) => this.name(r.requested_by) },
    { key: 'at', label: 'Asked', text: (r) => when(r.requested_at) },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'person' || c.key === 'role') : this.allCols));

  constructor() {
    super();
    effect(() => {
      const id = this.orgId();
      untracked(() => (id ? this.load(id) : this.state.set('empty')));
    });
  }

  name(id: string | null) { return this.members().find((m) => m.id === id)?.name ?? id ?? '—'; }
  role(r: string) { return roleName(roleLabel(r)); }

  private load(id: string) {
    this.rows.set(null);
    this.selected.set(null);
    this.data.query<RbacApprovalsMembers>('rbac.approvals.members', { id }).subscribe({ next: (m) => this.members.set(m), error: () => undefined });
    this.data.query<RbacApprovalsList>('rbac.approvals.list', { id }).subscribe({
      next: (r) => { this.rows.set(r); this.state.set(r.length ? 'ready' : 'empty'); },
      error: () => undefined,
    });
  }

  pick(r: RoleRequest) {
    this.selected.set(r);
    this.reason.set('');
    this.flash.set(null);
  }

  decide(r: RoleRequest, approve: boolean) {
    const body: RbacApprovalsDecideRequest = { approve, reason: this.reason() || null };
    this.data.command('rbac.approvals.decide', { id: this.orgId(), assignmentId: r.id, body }).subscribe({
      next: () => {
        this.flash.set({ kind: 'ok', text: `${approve ? 'Approved' : 'Rejected'}: ${this.role(r.role)} for ${this.name(r.subject)}.` });
        this.load(this.orgId()!);
      },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, RBAC_HINTS) }),
    });
  }
}
