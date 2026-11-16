import { ChangeDetectionStrategy, Component, computed, effect, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { words } from '../../core/text';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { Modal } from '../../../../../src/app/design-system/organisms/modal';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { refusal } from '../../../kyc/kyc-api';
import type { UsersSignInConfig } from './sign-in.widget';
import type { CredentialUser, UsersSignInAnswer, UsersSignInList } from './sign-in.contract';

type Fix = 'resend-link' | 'reset' | 'revoke-sessions';
const FIX: Record<Fix, { title: string; text: string; done: string }> = {
  'resend-link': { title: 'Send the set-up link again', text: 'A new one-time set-up link goes to the person.', done: 'A new set-up link is on its way to' },
  reset: { title: 'Reset the credential', text: 'Their current password and devices stop working; they set up again from a new link.', done: 'The credential reset is on its way for' },
  'revoke-sessions': { title: 'Sign out everywhere', text: 'Every session ends; they sign in again.', done: 'Every session is being ended for' },
};
const HINTS: Record<string, string> = {
  NO_CREDENTIAL: 'this person has no delivered sign-in credential yet',
  USER_NOT_IN_ORG: 'this person is not in this organization',
  ORG_ADMIN_REQUIRED: 'only an administrator of this organization or one above it may do this',
};

@Component({
  selector: 'mpw-users-sign-in',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpCell, Modal, MpButton, StatusBadge, MpAlert, EmptyState, MpPerm],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="Its people’s sign-in appears here." />
    } @else {
      <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" [selectable]="true"
                     placeholder="Search people" loading="Loading people…" empty="Nobody in this organization yet."
                     (rowClick)="publish('userId', $event.id)">
        <ng-template mpCell="status" let-u><status-badge [status]="u.status ?? 'PENDING'" /></ng-template>
        <ng-template mpCell="act" let-u>
          @if (u.subject) {
            <div class="acts">
              @if (u.status === 'PENDING') {
                <mp-button *mpPerm="'org.user:administer'" data-action="resend-link" variant="ghost" (onClick)="ask(u, 'resend-link')">Send link again</mp-button>
              } @else {
                <mp-button *mpPerm="'org.user:administer'" data-action="reset" variant="ghost" (onClick)="ask(u, 'reset')">Reset</mp-button>
                <mp-button *mpPerm="'org.user:administer'" data-action="sign-out" variant="ghost" (onClick)="ask(u, 'revoke-sessions')">Sign out</mp-button>
              }
            </div>
          } @else {<span class="dim">No credential yet</span>}
        </ng-template>
      </mp-data-table>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}

    <mp-modal [open]="!!asking()" [eyebrow]="asking() ? fix(asking()!.fix).title : ''" [title]="asking()?.user?.name ?? ''" size="narrow"
              (closed)="asking.set(null)">
      <p>{{ asking() ? fix(asking()!.fix).text : '' }}</p>
      <div modal-footer>
        <mp-button variant="ghost" (onClick)="asking.set(null)">Cancel</mp-button>
        <mp-button [attr.data-action]="asking() ? actionOf(asking()!.fix) : null" variant="danger" (onClick)="run()">Confirm</mp-button>
      </div>
    </mp-modal>
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .acts { display: flex; flex-wrap: wrap; gap: var(--sp-1, 4px); }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); }
  `],
})
export class UsersSignInWidget extends MiPayWidget<UsersSignInConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly rows = signal<UsersSignInList | null>(null);
  readonly asking = signal<{ user: CredentialUser; fix: Fix } | null>(null);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly fix = (f: Fix) => FIX[f];
  readonly actionOf = (f: Fix) => (f === 'revoke-sessions' ? 'sign-out' : f);

  private readonly allCols: DataCol<CredentialUser>[] = [
    { key: 'name', label: 'Person', text: (u) => u.name ?? '—', search: (u) => [u.name, u.username, u.phone] },
    { key: 'username', label: 'Login', text: (u) => u.username ?? '—', cls: 'mono' },
    { key: 'status', label: 'Status', text: (u) => words(u.status) },
    { key: 'link', label: 'Credential', text: (u) => (u.subject ? words(u.link_status) : 'none') },
    { key: 'act', label: '' },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'name' || c.key === 'act') : this.allCols));

  constructor() {
    super();
    effect(() => {
      const id = this.orgId();
      untracked(() => (id ? this.load(id) : this.state.set('empty')));
    });
  }

  private load(id: string) {
    this.rows.set(null);
    this.data.query<UsersSignInList>('users.sign-in.list', { id }).subscribe({ next: (r) => this.rows.set(r), error: () => undefined });
  }

  ask(user: CredentialUser, fix: Fix) {
    this.flash.set(null);
    this.asking.set({ user, fix });
  }

  run() {
    const a = this.asking();
    if (!a) return;
    this.asking.set(null);
    this.data.command<UsersSignInAnswer>(`users.sign-in.${a.fix}`, { id: this.orgId(), uid: a.user.id, body: {} }).subscribe({
      next: () => this.flash.set({ kind: 'ok', text: `${FIX[a.fix].done} ${a.user.name ?? a.user.username}.` }),
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, HINTS) }),
    });
  }
}
