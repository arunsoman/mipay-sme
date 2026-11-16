import { ChangeDetectionStrategy, Component, computed, effect, signal, untracked, viewChild } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { words, whenTime } from '../../core/text';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { MpEntityForm, MpFieldSchema } from '../../../../../src/app/design-system/organisms/mp-entity-form';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { OpsKycChecksConfig } from './kyc-checks.widget';
import type { KycCheck, OpsKycChecksList, OpsKycChecksRecord, OpsKycChecksRecordRequest, OpsKycOrgGet } from './kyc-checks.contract';

/** The org types the ops KYC endpoints know. */
const KNOWN = new Set(['merchant', 'supplier', 'distributor', 'manufacturer', 'lender']);

@Component({
  selector: 'mpw-ops-kyc-checks',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpCell, MpEntityForm, StatusBadge, MpAlert, EmptyState, MpPerm],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="Its KYC checks appear here." />
    } @else if (unsupported()) {
      <empty-state title="No KYC checks for this organization" hint="Checks are recorded on merchants, suppliers, distributors, manufacturers and lenders." />
    } @else {
      <div class="who"><b>{{ org()?.name ?? '…' }}</b> <span class="dim">{{ type(org()?.type) }}</span></div>
      <mp-data-table [cols]="cols()" [rows]="rows()" empty="No checks recorded yet." loading="Loading checks…" [searchable]="false">
        <ng-template mpCell="result" let-c><status-badge [status]="c.result ?? 'PENDING'" /></ng-template>
      </mp-data-table>
      @if (rows()) {
        <mp-entity-form #form *mpPerm="'ops.kyc_check:create'" data-action="record" [schema]="schema" [initial]="initial"
                        submitLabel="Record check" (submitted)="record($event)" />
      }
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .who { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; overflow-wrap: anywhere; }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); }
  `],
})
export class OpsKycChecksWidget extends MiPayWidget<OpsKycChecksConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly org = signal<OpsKycOrgGet | null>(null);
  readonly rows = signal<OpsKycChecksList | null>(null);
  readonly unsupported = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly type = words;

  readonly schema: MpFieldSchema[] = [
    { key: 'checkType', label: 'Check', kind: 'select', required: true,
      options: [{ value: 'IDENTITY', label: 'Identity' }, { value: 'SANCTIONS', label: 'Sanctions' }, { value: 'ADVERSE_MEDIA', label: 'Adverse media' }] },
    { key: 'result', label: 'Result', kind: 'select', required: true,
      options: [{ value: 'PASS', label: 'Pass' }, { value: 'FAIL', label: 'Fail' }, { value: 'MANUAL_REVIEW', label: 'Manual review' }] },
    { key: 'details', label: 'Details', kind: 'text', placeholder: 'Source, reference, what was found' },
  ];
  readonly initial = { checkType: 'IDENTITY', result: 'PASS' };

  private readonly allCols: DataCol<KycCheck>[] = [
    { key: 'checkType', label: 'Check', text: (c) => words(c.checkType) },
    { key: 'result', label: 'Result', text: (c) => words(c.result) },
    { key: 'details', label: 'Details', text: (c) => c.details ?? '—' },
    { key: 'checkedAt', label: 'When', text: (c) => whenTime(c.checkedAt) },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key !== 'details') : this.allCols));

  private readonly form = viewChild<MpEntityForm>('form');

  constructor() {
    super();
    effect(() => { const f = this.form(); if (f) untracked(() => queueMicrotask(() => f.clear())); });
    effect(() => {
      const id = this.orgId();
      untracked(() => (id ? this.load(id) : this.state.set('empty')));
    });
  }

  private target() {
    const o = this.org();
    return { entityType: (o?.type ?? '').toLowerCase(), entityId: o?.entityId ?? '' };
  }

  private load(id: string) {
    this.org.set(null);
    this.rows.set(null);
    this.unsupported.set(false);
    this.flash.set(null);
    this.data.query<OpsKycOrgGet>('ops.kyc.org.get', { id }).subscribe({
      next: (o) => {
        this.org.set(o);
        const t = this.target();
        if (!KNOWN.has(t.entityType) || !t.entityId) { this.unsupported.set(true); this.state.set('empty'); return; }
        this.loadChecks();
      },
      error: () => undefined,
    });
  }

  private loadChecks() {
    this.data.query<OpsKycChecksList>('ops.kyc-checks.list', this.target()).subscribe({
      next: (r) => this.rows.set(r),
      error: () => undefined,
    });
  }

  record(v: Record<string, string>) {
    const body: OpsKycChecksRecordRequest = { checkType: v['checkType'], result: v['result'], details: v['details']?.trim() || null };
    this.data.command<OpsKycChecksRecord>('ops.kyc-checks.record', { ...this.target(), body }).subscribe({
      next: (c) => {
        this.form()?.done('Recorded.');
        this.form()?.clear();
        this.flash.set({ kind: 'ok', text: `Recorded: ${words(c.checkType)} ${words(c.result).toLowerCase()}.` });
        this.loadChecks();
      },
      error: (e: unknown) => { this.form()?.failWire(e); this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }); },
    });
  }
}
