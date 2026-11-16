import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { roleName, words } from '../../core/text';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { Modal } from '../../../../../src/app/design-system/organisms/modal';
import { MpTabs, TabOption } from '../../../../../src/app/design-system/molecules/tabs';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { MpPerm } from '../../../web-core/perm-directive';
import { RBAC_HINTS, roleLabel } from '../../../rbac/rbac-api';
import { refusal } from '../../../kyc/kyc-api';
import type { RbacPlatformAssignmentsConfig } from './platform-assignments.widget';
import type {
  PlatformAssignment, PlatformAssignmentsAssignRequest, PlatformAssignmentsList, PlatformAssignmentsRoles, PlatformAssignmentsUsers,
} from './platform-assignments.contract';

@Component({
  selector: 'mpw-rbac-platform-assignments',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpCell, Modal, MpTabs, MpButton, StatusBadge, FormField, MpAlert, MpPerm],
  template: `
    <div class="bar">
      <mp-tabs [options]="tabs" [value]="status()" (valueChange)="setStatus($event)" label="Assignment status" />
      <mp-button *mpPerm="'rbac.assignment:create'" data-action="assign" (onClick)="openAssign()">Give a role</mp-button>
    </div>
    <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" [selectable]="true"
                   placeholder="Search people, roles or organizations" loading="Loading assignments…" empty="No assignments here."
                   (rowClick)="publish('userId', $event.subject)">
      <ng-template mpCell="status" let-a><status-badge [status]="a.status ?? 'PENDING'" /></ng-template>
      <ng-template mpCell="act" let-a>
        @if (a.status === 'ACTIVE' || a.status === 'PENDING_APPROVAL') {
          <mp-button *mpPerm="'rbac.assignment:administer'" data-action="revoke" variant="ghost" (onClick)="revoking.set(a)">Revoke</mp-button>
        }
      </ng-template>
    </mp-data-table>
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}

    <mp-modal [open]="assigning()" eyebrow="Role assignments" title="Give a role" (closed)="assigning.set(false)">
      <div class="form" data-assign-form>
        <mp-form-field key="subject" label="Person" kind="select" [required]="true" [options]="people()" [value]="aUser()" (changed)="pickUser($event)" />
        <mp-form-field key="role" label="Role" kind="select" [required]="true" [options]="roleOptions()" [value]="aRole()" (changed)="aRole.set($event)" />
        <mp-form-field key="tenant" label="In organization (id)" [value]="aTenant()" (changed)="aTenant.set($event)"
                       hint="The person’s own organization unless you change it." />
        <mp-form-field key="scope" label="Where it applies" kind="select" [options]="scopes" [value]="aScope()" (changed)="aScope.set($event)" />
        <mp-form-field key="reason" label="Reason" [value]="aReason()" (changed)="aReason.set($event)" />
        @if (assignError(); as m) {<mp-alert kind="bad">{{ m }}</mp-alert>}
      </div>
      <div modal-footer>
        <mp-button variant="ghost" (onClick)="assigning.set(false)">Cancel</mp-button>
        <mp-button data-action="assign" [disabled]="!aUser() || !aRole()" (onClick)="assign()">Give role</mp-button>
      </div>
    </mp-modal>

    <mp-modal [open]="!!revoking()" eyebrow="Revoke a role" [title]="revoking() ? name(revoking()!.subject) : ''" size="narrow"
              (closed)="revoking.set(null)">
      <p>Revoke <b>{{ revoking() ? role(revoking()!.role) : '' }}</b>?</p>
      <mp-form-field key="revokeReason" label="Reason" [value]="rReason()" (changed)="rReason.set($event)" />
      <div modal-footer>
        <mp-button variant="ghost" (onClick)="revoking.set(null)">Keep it</mp-button>
        <mp-button data-action="revoke" variant="danger" (onClick)="revoke()">Revoke role</mp-button>
      </div>
    </mp-modal>
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .bar { display: flex; flex-wrap: wrap; gap: var(--sp-2, 8px); justify-content: space-between; align-items: center; }
    .form { display: grid; gap: var(--sp-3, 12px); }
  `],
})
export class RbacPlatformAssignmentsWidget extends MiPayWidget<RbacPlatformAssignmentsConfig> {
  readonly tabs: TabOption[] = [
    { value: 'ACTIVE', label: 'Active' },
    { value: 'PENDING_APPROVAL', label: 'Waiting' },
    { value: '', label: 'All' },
  ];
  readonly scopes = [{ value: 'own', label: 'That organization' }, { value: 'subtree', label: 'That organization and below' }];
  readonly status = signal('ACTIVE');
  readonly rows = signal<PlatformAssignmentsList | null>(null);
  readonly users = signal<PlatformAssignmentsUsers>([]);
  readonly roles = signal<PlatformAssignmentsRoles>([]);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly people = computed(() => this.users().filter((u) => !!u.id)
    .map((u) => ({ value: u.id!, label: `${u.displayName ?? u.id} · ${words(u.kind)}` })));
  readonly roleOptions = computed(() => this.roles().filter((r) => !!r.name).map((r) => ({ value: r.name!, label: roleName(r.name) })));

  readonly assigning = signal(false);
  readonly aUser = signal('');
  readonly aRole = signal('');
  readonly aTenant = signal('');
  readonly aScope = signal('own');
  readonly aReason = signal('');
  readonly assignError = signal('');
  readonly revoking = signal<PlatformAssignment | null>(null);
  readonly rReason = signal('');

  private readonly allCols: DataCol<PlatformAssignment>[] = [
    { key: 'who', label: 'Person', text: (a) => this.name(a.subject), search: (a) => [this.name(a.subject), a.subject, a.tenant] },
    { key: 'role', label: 'Role', text: (a) => this.role(a.role) },
    { key: 'tenant', label: 'Organization', text: (a) => a.tenant ?? '—', cls: 'mono' },
    { key: 'status', label: 'Status', text: (a) => words(a.status) },
    { key: 'act', label: '' },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'who' || c.key === 'role' || c.key === 'act') : this.allCols));

  constructor() {
    super();
    this.load();
    this.data.query<PlatformAssignmentsUsers>('rbac.platform-assignments.users').subscribe({ next: (u) => this.users.set(u), error: () => undefined });
    this.data.query<PlatformAssignmentsRoles>('rbac.platform-assignments.roles').subscribe({ next: (r) => this.roles.set(r), error: () => undefined });
  }

  name(id: string | null) { return this.users().find((u) => u.id === id)?.displayName ?? id ?? '—'; }
  role(r: string | null) { return roleName(roleLabel(r ?? '')); }

  setStatus(v: string) { this.status.set(v); this.load(); }

  private load() {
    this.rows.set(null);
    this.data.query<PlatformAssignmentsList>('rbac.platform-assignments.list', this.status() ? { status: this.status() } : {}).subscribe({
      next: (r) => this.rows.set(r), error: () => undefined,
    });
  }

  openAssign() {
    this.aUser.set(''); this.aRole.set(''); this.aTenant.set(''); this.aScope.set('own'); this.aReason.set('');
    this.assignError.set('');
    this.assigning.set(true);
  }

  pickUser(id: string) {
    this.aUser.set(id);
    this.aTenant.set(this.users().find((u) => u.id === id)?.orgId ?? '');
  }

  assign() {
    const body: PlatformAssignmentsAssignRequest = {
      subject: this.aUser(), role: this.aRole(), tenant: this.aTenant() || null, scope: this.aScope(), expiresAt: null,
      reason: this.aReason() || null,
    };
    this.data.command<PlatformAssignment>('rbac.platform-assignments.assign', { body }).subscribe({
      next: (a) => {
        this.assigning.set(false);
        this.flash.set({ kind: 'ok', text: a.status === 'PENDING_APPROVAL'
          ? `${this.role(a.role)} is waiting for a second person.` : `${this.role(a.role)} is given to ${this.name(a.subject)}.` });
        this.load();
      },
      error: (e: unknown) => this.assignError.set(refusal(e, RBAC_HINTS)),
    });
  }

  revoke() {
    const a = this.revoking();
    if (!a) return;
    const params: Record<string, unknown> = { id: a.id };
    if (this.rReason()) params['reason'] = this.rReason();
    this.data.command<PlatformAssignment>('rbac.platform-assignments.revoke', params).subscribe({
      next: () => { this.revoking.set(null); this.rReason.set(''); this.flash.set({ kind: 'ok', text: `${this.role(a.role)} is revoked.` }); this.load(); },
      error: (e: unknown) => { this.revoking.set(null); this.flash.set({ kind: 'bad', text: refusal(e, RBAC_HINTS) }); },
    });
  }
}
