import { ChangeDetectionStrategy, Component, computed, effect, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { roleName, words } from '../../core/text';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { Modal } from '../../../../../src/app/design-system/organisms/modal';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { RBAC_HINTS, roleLabel } from '../../../rbac/rbac-api';
import { refusal } from '../../../kyc/kyc-api';
import type { RbacAssignmentsConfig } from './assignments.widget';
import type {
  Assignment, OrgMembers, RbacAssignmentsAssign, RbacAssignmentsAssignRequest, RbacAssignmentsList, RbacAssignmentsRevokeRequest,
  RbacAssignmentsRoles,
} from './assignments.contract';

@Component({
  selector: 'mpw-rbac-assignments',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpCell, Modal, MpButton, StatusBadge, FormField, MpAlert, EmptyState, MpPerm],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="Who holds which role there appears here." />
    } @else {
      <div class="bar">
        <mp-button *mpPerm="'rbac.assignment:create'" data-action="assign" (onClick)="openAssign()">Give a role</mp-button>
      </div>
      <mp-data-table [cols]="cols()" [rows]="current()" [pageSize]="config.pageSize" [selectable]="true"
                     placeholder="Search people or roles" loading="Loading assignments…" empty="Nobody holds a role yet."
                     (rowClick)="publish('userId', $event.subject)">
        <ng-template mpCell="status" let-a><status-badge [status]="a.status" /></ng-template>
        <ng-template mpCell="act" let-a>
          <mp-button *mpPerm="'rbac.assignment:administer'" data-action="revoke" variant="ghost" (onClick)="revoking.set(a)">Take away</mp-button>
        </ng-template>
      </mp-data-table>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}

    <mp-modal [open]="assigning()" eyebrow="Role assignments" title="Give a role" (closed)="assigning.set(false)">
      <div class="form" data-assign-form>
        <mp-form-field key="userId" label="Person" kind="select" [required]="true" [options]="people()" [value]="aUser()" (changed)="aUser.set($event)" />
        <mp-form-field key="role" label="Role" kind="select" [required]="true" [options]="roleOptions()" [value]="aRole()" (changed)="aRole.set($event)" />
        <mp-form-field key="scope" label="Where it applies" kind="select" [options]="scopes" [value]="aScope()" (changed)="aScope.set($event)" />
        <mp-form-field key="reason" label="Reason" [value]="aReason()" (changed)="aReason.set($event)" hint="Kept with the assignment." />
        @if (assignError(); as m) {<mp-alert kind="bad">{{ m }}</mp-alert>}
      </div>
      <div modal-footer>
        <mp-button variant="ghost" (onClick)="assigning.set(false)">Cancel</mp-button>
        <mp-button data-action="assign" [disabled]="!aUser() || !aRole()" (onClick)="assign()">Give role</mp-button>
      </div>
    </mp-modal>

    <mp-modal [open]="!!revoking()" eyebrow="Take a role away" [title]="revoking() ? who(revoking()!) : ''" size="narrow"
              (closed)="revoking.set(null)">
      <p>Take <b>{{ revoking() ? role(revoking()!.role) : '' }}</b> away?</p>
      <mp-form-field key="revokeReason" label="Reason" [value]="rReason()" (changed)="rReason.set($event)" />
      <div modal-footer>
        <mp-button variant="ghost" (onClick)="revoking.set(null)">Keep it</mp-button>
        <mp-button data-action="revoke" variant="danger" (onClick)="revoke()">Take away</mp-button>
      </div>
    </mp-modal>
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .bar { display: flex; justify-content: flex-end; }
    .form { display: grid; gap: var(--sp-3, 12px); }
  `],
})
export class RbacAssignmentsWidget extends MiPayWidget<RbacAssignmentsConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly rows = signal<RbacAssignmentsList | null>(null);
  readonly members = signal<OrgMembers>([]);
  readonly roles = signal<RbacAssignmentsRoles | null>(null);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly scopes = [{ value: 'own', label: 'This organization' }, { value: 'subtree', label: 'This organization and below' }];

  /** live assignments: held or waiting for approval */
  readonly current = computed(() => this.rows()?.filter((a) => a.status === 'ACTIVE' || a.status === 'PENDING_APPROVAL') ?? null);
  readonly people = computed(() => this.members().filter((m) => m.status !== 'ARCHIVED')
    .map((m) => ({ value: m.id, label: `${m.name ?? m.username ?? m.id}${m.username ? ' (' + m.username + ')' : ''}` })));
  readonly roleOptions = computed(() => {
    const v = this.roles();
    return v ? [...v.roles, ...v.org_roles].map((r) => ({ value: r.name, label: roleName(roleLabel(r.name)) })) : [];
  });

  readonly assigning = signal(false);
  readonly aUser = signal('');
  readonly aRole = signal('');
  readonly aScope = signal('own');
  readonly aReason = signal('');
  readonly assignError = signal('');
  readonly revoking = signal<Assignment | null>(null);
  readonly rReason = signal('');

  private readonly allCols: DataCol<Assignment>[] = [
    { key: 'person', label: 'Person', text: (a) => this.who(a), search: (a) => [a.userName, a.username, a.subject] },
    { key: 'role', label: 'Role', text: (a) => this.role(a.role) },
    { key: 'scope', label: 'Applies to', text: (a) => (a.scope === 'subtree' ? 'and below' : 'this org') },
    { key: 'status', label: 'Status', text: (a) => words(a.status) },
    { key: 'act', label: '' },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'person' || c.key === 'role' || c.key === 'act') : this.allCols));

  constructor() {
    super();
    effect(() => {
      const id = this.orgId();
      untracked(() => (id ? this.load(id) : this.state.set('empty')));
    });
  }

  who(a: Assignment) { return a.userName ?? a.username ?? a.subject; }
  role(r: string) { return roleName(roleLabel(r)); }

  private load(id: string) {
    this.rows.set(null);
    this.data.query<RbacAssignmentsList>('rbac.assignments.list', { id }).subscribe({
      next: (r) => this.rows.set(r),
      error: () => undefined,
    });
    this.data.query<OrgMembers>('rbac.assignments.members', { id }).subscribe({ next: (m) => this.members.set(m), error: () => undefined });
    this.data.query<RbacAssignmentsRoles>('rbac.assignments.roles', { id }).subscribe({ next: (r) => this.roles.set(r), error: () => undefined });
  }

  private reloadList() {
    this.data.query<RbacAssignmentsList>('rbac.assignments.list', { id: this.orgId() }).subscribe({
      next: (r) => this.rows.set(r), error: () => undefined,
    });
  }

  openAssign() {
    this.aUser.set('');
    this.aRole.set('');
    this.aScope.set('own');
    this.aReason.set('');
    this.assignError.set('');
    this.assigning.set(true);
  }

  assign() {
    const body: RbacAssignmentsAssignRequest = {
      userId: this.aUser(), role: this.aRole(), scope: this.aScope(), expiresAt: null, reason: this.aReason() || null,
    };
    this.data.command<RbacAssignmentsAssign>('rbac.assignments.assign', { id: this.orgId(), body }).subscribe({
      next: (a) => {
        this.assigning.set(false);
        this.flash.set({ kind: 'ok', text: a.status === 'PENDING_APPROVAL'
          ? `${this.role(a.role)} is waiting for a second person to approve it.` : `${this.role(a.role)} is given.` });
        this.reloadList();
      },
      error: (e: unknown) => this.assignError.set(refusal(e, RBAC_HINTS)),
    });
  }

  revoke() {
    const a = this.revoking();
    if (!a) return;
    const body: RbacAssignmentsRevokeRequest = { reason: this.rReason() || null };
    this.data.command('rbac.assignments.revoke', { id: this.orgId(), assignmentId: a.id, body }).subscribe({
      next: () => {
        this.revoking.set(null);
        this.rReason.set('');
        this.flash.set({ kind: 'ok', text: `${this.role(a.role)} is taken away from ${this.who(a)}.` });
        this.reloadList();
      },
      error: (e: unknown) => { this.revoking.set(null); this.flash.set({ kind: 'bad', text: refusal(e, RBAC_HINTS) }); },
    });
  }
}
