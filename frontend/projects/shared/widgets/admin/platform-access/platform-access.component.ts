import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { whenTime, words } from '../../core/text';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { Modal } from '../../../../../src/app/design-system/organisms/modal';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { MpPerm } from '../../../web-core/perm-directive';
import { refusal } from '../../../kyc/kyc-api';
import type { AdminPlatformAccessConfig } from './platform-access.widget';
import type {
  AccessLink, AccessSession, AccessUser, AdminAccessDetail, AdminAccessDisable, AdminAccessList, AdminAccessRevoke,
} from './platform-access.contract';

@Component({
  selector: 'mpw-admin-platform-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpCell, Modal, MpButton, StatusBadge, MpAlert, MpPerm],
  template: `
    <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" [selectable]="true"
                   placeholder="Search users, phones or roles" loading="Loading access…" empty="No users."
                   (rowClick)="pick($event)">
      <ng-template mpCell="status" let-u><status-badge [status]="u.status ?? 'PENDING'" /></ng-template>
    </mp-data-table>

    @if (detail(); as d) {
      <section class="detail" data-selected-user>
        <div class="who"><b class="mono">{{ d.userId }}</b> <span class="dim">{{ d.phone }} · {{ type(d.role) }}</span>
          <mp-button *mpPerm="'admin.access:administer'" data-action="revoke" variant="danger" (onClick)="confirming.set(true)">End every session</mp-button>
        </div>
        <b>Sign-in links</b>
        <mp-data-table [cols]="linkCols" [rows]="d.links ?? []" [searchable]="false" empty="No sign-in link.">
          <ng-template mpCell="status" let-l><status-badge [status]="l.status ?? 'PENDING'" /></ng-template>
          <ng-template mpCell="act" let-l>
            @if (l.status !== 'DISABLED') {
              <mp-button *mpPerm="'admin.access:administer'" data-action="disable-link" variant="ghost" (onClick)="disable(l)">Disable</mp-button>
            }
          </ng-template>
        </mp-data-table>
        <b>Sessions</b>
        <mp-data-table [cols]="sessionCols" [rows]="d.sessions ?? []" [searchable]="false" empty="No sessions." />
      </section>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}

    <mp-modal [open]="confirming()" eyebrow="End every session" [title]="detail()?.userId ?? ''" size="narrow" (closed)="confirming.set(false)">
      <p>Every session of this user ends now and an incident is recorded. They can sign in again.</p>
      <div modal-footer>
        <mp-button variant="ghost" (onClick)="confirming.set(false)">Cancel</mp-button>
        <mp-button data-action="revoke" variant="danger" (onClick)="revoke()">End sessions</mp-button>
      </div>
    </mp-modal>
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .detail { display: grid; gap: var(--sp-2, 8px); padding-top: var(--sp-3, 12px); border-top: 1px solid var(--a-border, #2b2b2b); }
    .who { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: center; overflow-wrap: anywhere; }
    .mono { font-family: var(--font-mono, monospace); }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); }
  `],
})
export class AdminPlatformAccessWidget extends MiPayWidget<AdminPlatformAccessConfig> {
  readonly rows = signal<AdminAccessList | null>(null);
  readonly detail = signal<AdminAccessDetail | null>(null);
  readonly confirming = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly type = words;

  private readonly allCols: DataCol<AccessUser>[] = [
    { key: 'userId', label: 'User', text: (u) => u.userId ?? '—', cls: 'mono', search: (u) => [u.userId, u.phone, u.role] },
    { key: 'phone', label: 'Mobile', text: (u) => u.phone ?? '—' },
    { key: 'role', label: 'Role', text: (u) => words(u.role) },
    { key: 'status', label: 'Status', text: (u) => words(u.status) },
    { key: 'sessions', label: 'Live sessions', text: (u) => u.activeSessions },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'userId' || c.key === 'sessions') : this.allCols));
  readonly linkCols: DataCol<AccessLink>[] = [
    { key: 'provider', label: 'Provider', text: (l) => l.provider ?? '—' },
    { key: 'status', label: 'Status', text: (l) => words(l.status) },
    { key: 'last', label: 'Last sign-in', text: (l) => whenTime(l.lastLoginAt) },
    { key: 'act', label: '' },
  ];
  readonly sessionCols: DataCol<AccessSession>[] = [
    { key: 'device', label: 'Device', text: (s) => s.deviceFingerprint ?? '—', cls: 'mono' },
    { key: 'created', label: 'Started', text: (s) => whenTime(s.createdAt) },
    { key: 'live', label: 'Live', text: (s) => (s.live ? 'yes' : 'no') },
  ];

  constructor() {
    super();
    this.load();
  }

  private load() {
    this.data.query<AdminAccessList>('admin.access.list').subscribe({
      next: (r) => { this.rows.set(r); if (!r.length) this.state.set('empty'); },
      error: () => undefined,
    });
  }

  private loadDetail(userId: string) {
    this.data.query<AdminAccessDetail>('admin.access.detail', { userId }).subscribe({ next: (d) => this.detail.set(d), error: () => undefined });
  }

  pick(u: AccessUser) {
    if (!u.userId) return;
    this.flash.set(null);
    this.publish('userId', u.userId);
    this.loadDetail(u.userId);
  }

  revoke() {
    const id = this.detail()?.userId;
    this.confirming.set(false);
    if (!id) return;
    this.data.command<AdminAccessRevoke>('admin.access.revoke', { userId: id, body: {} }).subscribe({
      next: (r) => {
        this.flash.set({ kind: 'ok', text: `${r.revoked} session(s) ended${r.incidentId ? ` (incident ${r.incidentId})` : ''}.` });
        this.loadDetail(id);
        this.load();
      },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e) }),
    });
  }

  disable(l: AccessLink) {
    const id = this.detail()?.userId;
    this.data.command<AdminAccessDisable>('admin.access.disable-link', { linkId: l.id, body: {} }).subscribe({
      next: (r) => { this.flash.set({ kind: 'ok', text: `The sign-in link is ${words(r.linkStatus).toLowerCase()}.` }); if (id) this.loadDetail(id); },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e) }),
    });
  }
}
