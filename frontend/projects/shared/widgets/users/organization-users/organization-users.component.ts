import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, signal, viewChild } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { WidgetProblem } from '../../core/port';
import { refusal } from '../../../kyc/kyc-api';
import { MpPerm } from '../../../web-core/perm-directive';
import { PageHeader } from '../../../../../src/app/design-system/organisms/page-header';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { MpTabs, TabOption } from '../../../../../src/app/design-system/molecules/tabs';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { MpCard } from '../../../../../src/app/design-system/atoms/mp-card';
import { Modal } from '../../../../../src/app/design-system/organisms/modal';
import { MpEntityForm, MpFieldSchema } from '../../../../../src/app/design-system/organisms/mp-entity-form';
import { MpSpinner } from '../../../../../src/app/design-system/atoms/mp-spinner';
import type { UsersOrganizationUsersConfig } from './organization-users.widget';
import type {
  UsersOrganizationCreate,
  UsersOrganizationCreateRequest,
  UsersOrganizationDeactivate,
  UsersOrganizationList,
  UsersOrganizationSetupLinkResponse,
} from './organization-users.contract';

type Person = UsersOrganizationList[number];
type Filter = 'all' | 'pending' | 'active' | 'suspended' | 'archived';

interface SetupLinkView {
  readonly url: string;
  readonly expiresAt?: string;
  readonly delivered?: boolean;
  readonly message?: string;
}

const USER_HINTS: Record<string, string> = {
  DUPLICATE_USERNAME: 'Choose another username.',
  LAST_ADMIN: 'An organization must keep at least one active administrator.',
  ALREADY_SUSPENDED: 'This person is already deactivated.',
  NO_CREDENTIAL: 'Credential enrolment has not completed yet; try again shortly.',
  USER_ACTIVE: 'Setup links are available only while a person is pending activation.',
  DUPLICATE_PHONE: 'That mobile number is already registered.',
  NO_ORG: 'Your account is not linked to an organization.',
  ADMIN_REQUIRED: 'Only an organization administrator can do this.',
};

/** Organization users: list, add, issue a setup link, and deactivate. */
@Component({
  selector: 'mpw-users-organization-users',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    PageHeader,
    DataTable,
    MpCell,
    MpTabs,
    StatusBadge,
    EmptyState,
    MpAlert,
    MpButton,
    MpCard,
    Modal,
    MpEntityForm,
    MpSpinner,
    MpPerm,
    NgTemplateOutlet,
  ],
  template: `
    <page-header title="Organization users" subtitle="People in your organization and their access status.">
      <div actions>
        <mp-button *mpPerm="'org.user:create'" data-action="add" variant="primary"
                   [disabled]="busy()" (onClick)="openAdd()">Add person</mp-button>
      </div>
    </page-header>

    @if (loadError(); as message) {<mp-alert kind="bad">{{ message }}</mp-alert>}
    @if (flash(); as message) {<mp-alert [kind]="message.kind">{{ message.text }}</mp-alert>}

    @if (rows(); as people) {
      @if (people.length) {
        <mp-tabs [options]="tabs()" [value]="filter()" (valueChange)="setFilter($event)" label="User status" />
        <mp-data-table [cols]="cols()" [rows]="shown()" [pageSize]="config.pageSize"
                       placeholder="Search people, contacts, roles"
                       empty="No people in this organization yet."
                       noMatch="Nobody matches your search.">
          <ng-template mpCell="person" let-person>
            <div class="person">
              <b>{{ person.name || 'Unnamed person' }}</b>
              <span class="muted mono">{{ person.username || 'No username yet' }}</span>
              <!-- phones have no actions column: the actions sit under the person -->
              @if (phone()) {<ng-container *ngTemplateOutlet="rowActions; context: { $implicit: person }" />}
            </div>
          </ng-template>
          <ng-template mpCell="contact" let-person>
            <div class="contact">
              <span>{{ person.phone || 'No mobile number' }}</span>
              <span class="muted">{{ person.email || 'No email' }}</span>
            </div>
          </ng-template>
          <ng-template mpCell="role" let-person>
            <div class="contact">
              <b>{{ titleCase(person.orgRole) }}</b>
              <span class="muted">{{ titleCase(person.role) }}</span>
            </div>
          </ng-template>
          <ng-template mpCell="status" let-person>
            <status-badge [status]="person.status ?? 'UNKNOWN'" />
          </ng-template>
          <ng-template mpCell="actions" let-person>
            <ng-container *ngTemplateOutlet="rowActions; context: { $implicit: person }" />
          </ng-template>
        </mp-data-table>
        <ng-template #rowActions let-person>
          <div class="actions">
              @if (person.status === 'PENDING') {
                <mp-button *mpPerm="'org.user:administer'" data-action="setup-link" variant="ghost"
                           [disabled]="busy()" (onClick)="issueSetup(person)">Set-up link</mp-button>
              }
              @if (person.status !== 'SUSPENDED' && person.status !== 'ARCHIVED') {
                <mp-button *mpPerm="'org.user:delete'" data-action="deactivate" variant="ghost"
                           [disabled]="busy()" (onClick)="openDeactivate(person)">Deactivate</mp-button>
              }
            </div>
        </ng-template>
      } @else {
        <empty-state title="No people yet"
                     hint="People you add to your organization appear here with their access status." />
      }
    } @else if (!loadError()) {
      <mp-data-table [cols]="cols()" [rows]="null" loading="Loading organization users…" />
    }

    <mp-modal [open]="adding()" eyebrow="Organization users" title="Add a person"
              (closed)="closeAdd()">
      <mp-entity-form #addForm *mpPerm="'org.user:create'" data-action="add"
                      [schema]="addSchema" [initial]="addInitial" submitLabel="Add person"
                      (submitted)="createPerson($event)" />
    </mp-modal>

    <mp-modal [open]="!!setupFor()" eyebrow="Setup link" [title]="setupFor()?.name ?? 'Setup link'"
              (closed)="closeSetup()">
      @if (busy()) {
        <mp-spinner label="Issuing a secure setup link…" />
      } @else if (setupError(); as message) {
        <mp-alert kind="bad">{{ message }}</mp-alert>
      } @else if (setupLink(); as link) {
        <mp-card title="One-time setup link" [subtitle]="link.expiresAt ? 'Expires ' + formatDate(link.expiresAt) : ''">
          <code class="link">{{ link.url }}</code>
          <p class="muted note">
            {{ link.delivered === false ? 'Delivery failed. ' : '' }}{{ link.message || 'Share this link securely with the new user.' }}
          </p>
        </mp-card>
        <div class="modal-actions">
          <mp-button variant="ghost" (onClick)="closeSetup()">Close</mp-button>
          <mp-button variant="primary" (onClick)="copySetupLink()">{{ copied() ? 'Copied' : 'Copy link' }}</mp-button>
        </div>
      }
    </mp-modal>

    <mp-modal [open]="!!deactivating()" eyebrow="Organization users"
              [title]="'Deactivate ' + (deactivating()?.name ?? 'person')"
              (closed)="closeDeactivate()">
      <p class="confirm">
        This immediately refuses the person's access and revokes their sessions. Their records remain in the organization.
      </p>
      @if (deactivateError(); as message) {<mp-alert kind="bad">{{ message }}</mp-alert>}
      <div modal-footer>
        <mp-button variant="ghost" [disabled]="busy()" (onClick)="closeDeactivate()">Cancel</mp-button>
        <mp-button *mpPerm="'org.user:delete'" data-action="deactivate" variant="danger"
                   [loading]="busy()" (onClick)="deactivatePerson()">Deactivate person</mp-button>
      </div>
    </mp-modal>
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3); min-width: 0; }
    .person, .contact { display: grid; gap: 2px; min-width: 0; }
    .person b, .contact b { font-weight: 650; }
    .muted { color: var(--mp-text-dim); font-size: var(--fs-xs); }
    .mono { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; }
    .actions { display: flex; gap: var(--sp-2); justify-content: flex-end; flex-wrap: wrap; }
    .link { display: block; overflow-wrap: anywhere; white-space: pre-wrap; padding: var(--sp-3);
            border-radius: var(--radius-1); background: var(--mp-bg); font-size: var(--fs-sm); }
    .note { margin: var(--sp-3) 0 0; }
    .confirm { margin: 0; }
    .modal-actions { display: flex; justify-content: flex-end; gap: var(--sp-2); }
    @container (max-width: 560px) {
      .actions { justify-content: flex-start; }
    }
  `],
})
export class UsersOrganizationUsersWidget extends MiPayWidget<UsersOrganizationUsersConfig> {
  readonly rows = signal<UsersOrganizationList | null>(null);
  readonly loadError = signal('');
  readonly flash = signal<{ kind: 'ok' | 'bad' | 'info'; text: string } | null>(null);
  readonly filter = signal<Filter>('all');
  readonly busy = signal(false);

  readonly adding = signal(false);
  readonly setupFor = signal<Person | null>(null);
  readonly setupLink = signal<SetupLinkView | null>(null);
  readonly setupError = signal('');
  readonly copied = signal(false);
  readonly deactivating = signal<Person | null>(null);
  readonly deactivateError = signal('');

  readonly addForm = viewChild<MpEntityForm>('addForm');
  readonly addInitial: Record<string, string> = { name: '', phone: '', email: '', orgRole: 'STAFF' };
  readonly addSchema: MpFieldSchema[] = [
    { key: 'name', label: 'Full name', kind: 'text', required: true, placeholder: 'e.g. Ndapewa Shikongo' },
    { key: 'phone', label: 'Mobile number', kind: 'text', required: true, placeholder: '08x xxx xxxx' },
    { key: 'email', label: 'Email address', kind: 'text', required: true, placeholder: 'name@business.example' },
    {
      key: 'orgRole', label: 'Organization role', kind: 'select', required: true,
      hint: 'Staff can operate the business; administrators can also manage people and access.',
      options: [{ value: 'STAFF', label: 'Staff' }, { value: 'ADMIN', label: 'Administrator' }],
    },
  ];

  private readonly allCols: DataCol<Person>[] = [
    { key: 'person', label: 'Person', search: (person) => [person.name, person.username] },
    { key: 'contact', label: 'Contact', search: (person) => [person.phone, person.email] },
    { key: 'role', label: 'Role', search: (person) => [person.orgRole, person.role] },
    { key: 'status', label: 'Status' },
    { key: 'actions', label: '' },
  ];

  /** Mobile first: on a phone the actions move under the person (no actions column). */
  readonly phone = computed(() => this.size().tier === 'xs');

  readonly cols = computed(() => {
    const tier = this.size().tier;
    if (tier === 'xs') return this.allCols.filter((column) => ['person', 'status'].includes(column.key));
    if (tier === 'sm') return this.allCols.filter((column) => column.key !== 'role');
    return this.allCols;
  });

  readonly shown = computed(() => {
    const selected = this.filter();
    return (this.rows() ?? []).filter((person) => selected === 'all' || person.status === selected.toUpperCase());
  });

  readonly tabs = computed<TabOption<Filter>[]>(() => {
    const count = (status: string) => (this.rows() ?? []).filter((person) => person.status === status).length;
    return [
      { value: 'all', label: `All (${(this.rows() ?? []).length})` },
      { value: 'pending', label: `Pending (${count('PENDING')})` },
      { value: 'active', label: `Active (${count('ACTIVE')})` },
      { value: 'suspended', label: `Deactivated (${count('SUSPENDED')})` },
      { value: 'archived', label: `Archived (${count('ARCHIVED')})` },
    ];
  });

  constructor() {
    super();
    effect(() => {
      if (!this.adding()) return;
      queueMicrotask(() => this.addForm()?.clear());
    });
    this.load();
  }

  private load() {
    this.loadError.set('');
    this.data.query<UsersOrganizationList>('users.organization.list').subscribe({
      next: (people) => {
        this.rows.set(people);
        this.state.set(people.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => this.loadError.set(refusal(error, USER_HINTS)),
    });
  }

  setFilter(value: string) { this.filter.set(value as Filter); }

  titleCase(value: string | null | undefined) {
    return value ? value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ') : '—';
  }

  formatDate(value: string) {
    return new Date(value).toLocaleString('en-NA', {
      day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
    });
  }

  openAdd() {
    this.flash.set(null);
    this.adding.set(true);
  }

  closeAdd() {
    this.adding.set(false);
    this.addForm()?.clear();
  }

  createPerson(values: Record<string, string>) {
    const body: UsersOrganizationCreateRequest = {
      name: (values['name'] ?? '').trim(),
      phone: (values['phone'] ?? '').trim(),
      email: (values['email'] ?? '').trim(),
      username: null,
      orgRole: values['orgRole'] === 'ADMIN' ? 'ADMIN' : 'STAFF',
      password: null,
    };
    this.busy.set(true);
    this.data.command<UsersOrganizationCreate>('users.organization.create', { body }).subscribe({
      next: (created) => {
        this.busy.set(false);
        this.addForm()?.done(`${created.name ?? 'Person'} added.`);
        this.adding.set(false);
        const person: Person = {
          id: created.id,
          name: created.name,
          username: created.username,
          role: created.role,
          orgRole: created.orgRole,
          status: created.status,
          phone: created.phone,
          email: created.email,
        };
        this.rows.update((people) => people ? [...people, person] : [person]);
        this.flash.set({ kind: 'ok', text: `${created.name ?? 'Person'} added as ${this.titleCase(created.orgRole)}.` });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.addForm()?.failWire(error);
        if (error instanceof WidgetProblem && error.problem.fields) {
          this.addForm()?.errors.set(error.problem.fields);
        }
      },
    });
  }

  issueSetup(person: Person) {
    this.flash.set(null);
    this.setupFor.set(person);
    this.setupLink.set(null);
    this.setupError.set('');
    this.copied.set(false);
    this.busy.set(true);
    this.data.command<UsersOrganizationSetupLinkResponse>('users.organization.setup-link', {
      id: person.id,
      body: {},
    }).subscribe({
      next: (result) => {
        this.busy.set(false);
        if ('setupLink' in result) {
          this.setupLink.set({
            url: result.setupLink,
            expiresAt: result.expiresAt,
            delivered: result.delivered,
            message: result.deliveryMessage,
          });
        } else {
          this.setupLink.set({ url: result.label ?? '', message: result.message });
        }
        if (!this.setupLink()?.url) this.setupError.set('The server did not return a setup link.');
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.setupError.set(refusal(error, USER_HINTS));
      },
    });
  }

  closeSetup() {
    this.setupFor.set(null);
    this.setupLink.set(null);
    this.setupError.set('');
    this.copied.set(false);
  }

  async copySetupLink() {
    const link = this.setupLink()?.url;
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link);
      this.copied.set(true);
    } catch {
      this.copied.set(false);
    }
  }

  openDeactivate(person: Person) {
    this.flash.set(null);
    this.deactivateError.set('');
    this.deactivating.set(person);
  }

  closeDeactivate() {
    this.deactivating.set(null);
    this.deactivateError.set('');
  }

  deactivatePerson() {
    const person = this.deactivating();
    if (!person) return;
    this.busy.set(true);
    this.deactivateError.set('');
    this.data.command<UsersOrganizationDeactivate>('users.organization.deactivate', {
      id: person.id,
      body: {},
    }).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.rows.update((people) => (people ?? []).map((row) => row.id === person.id ? { ...row, status: 'SUSPENDED' } : row));
        this.deactivating.set(null);
        this.flash.set({ kind: 'ok', text: result.message ?? `${person.name ?? 'Person'} was deactivated.` });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.deactivateError.set(refusal(error, USER_HINTS));
      },
    });
  }
}
