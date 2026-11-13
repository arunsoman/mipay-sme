import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { KycPayload } from '../../core/api';
import { DataCol, DATA_TABLE } from '../../../../../../src/app/design-system/organisms/data-table';

/** Read-only rendering of a KYC application document for reviewers. */
@Component({
  selector: 'mpa-kyc-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [...DATA_TABLE],
  template: `
    @let d = doc();
    <div class="kv">
      <section>
        <h4>Business Profile &amp; General Information</h4>
        @for (f of pairs(d.businessProfile); track f[0]) {<div class="r"><span>{{ f[0] }}</span><b>{{ f[1] }}</b></div>}
      </section>
      <section>
        <h4>Operational &amp; Store Details</h4>
        @for (f of pairs(d.operationalDetails); track f[0]) {<div class="r"><span>{{ f[0] }}</span><b>{{ f[1] }}</b></div>}
      </section>
      <section class="wide">
        <h4>Registered &amp; Operational Address</h4>
        @for (a of d.addresses ?? []; track $index) {
          <div class="line"><span class="chip small">{{ a['addressType'] === 'OPERATING' ? 'Operational / store' : 'Registered office' }}</span>
            {{ addressText(a) }}</div>
        } @empty {<div class="muted">None</div>}
      </section>
      <section class="wide">
        <h4>Ultimate Beneficial Owners (UBO) &amp; Key Management</h4>
        <mp-data-table [cols]="uboCols" [rows]="d.ubos ?? []" placeholder="Search owners" empty="No owners listed.">
          <ng-template mpCell="dash" let-v="value">{{ v ?? '—' }}</ng-template>
                  <ng-template mpCell="ownershipPercentage" let-v="value">{{ v }}%</ng-template>
          <ng-template mpCell="politicallyExposedPerson" let-v="value">@if (v) {<span class="chip warn small">PEP</span>} @else {no}</ng-template>
        </mp-data-table>
      </section>
      <section class="wide">
        <h4>Settlement &amp; Payout Bank Account Details</h4>
        @for (b of d.bankAccounts ?? []; track $index) {
          <div class="line">{{ b['beneficiaryName'] }} · {{ b['bankCode'] ?? b['bankName'] }}
            <span class="mono">{{ b['institutionCode'] ?? b['branch'] ?? '' }} {{ b['accountNumber'] ?? '' }}</span>
            <span class="chip small">{{ b['accountType'] }}</span></div>
        } @empty {<div class="muted">None</div>}
      </section>
    </div>
  `,
  styles: [`
    .kv { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
    .wide { grid-column: 1 / -1; }
    h4 { margin: 0 0 6px; font-size: 12.5px; color: var(--a-dim); text-transform: uppercase; letter-spacing: .04em; }
    .r { display: flex; justify-content: space-between; gap: 10px; font-size: var(--fs-sm); padding: 2px 0; }
    .r span { color: var(--a-dim); }
    .r b { font-weight: 500; text-align: right; word-break: break-word; }
    .line { font-size: var(--fs-sm); padding: 3px 0; }
    @media (max-width: 800px) { .kv { grid-template-columns: 1fr; } }
  `],
})
export class KycView {
  readonly doc = input.required<KycPayload>();
  readonly uboCols: DataCol<Record<string, unknown>>[] = [
    { key: 'fullLegalName', label: 'Name' },
    { key: 'dateOfBirth', label: 'Born', type: 'dash' },
    { key: 'nationality', label: 'Nationality' },
    { key: 'residentialAddress', label: 'Residential address', type: 'dash' },
    { key: 'id', label: 'ID', cls: 'mono', text: (u) => `${u['identificationType'] ?? ''} ${u['identificationNumber'] ?? ''}` },
    { key: 'identificationExpiryDate', label: 'Expires' },
    { key: 'ownershipPercentage', label: 'Owns' },
    { key: 'politicallyExposedPerson', label: 'PEP', search: (u) => (u['politicallyExposedPerson'] ? 'PEP' : '') },
  ];

  addressText(a: Record<string, string | null>) {
    return ['streetLine', 'town', 'region', 'postalCode', 'country'].map((k) => a[k]).filter((x) => !!x).join(', ');
  }

  pairs(o: Record<string, unknown> | null): [string, string][] {
    if (!o) return [];
    return Object.entries(o).map(([k, v]) => [
      k.replace(/([A-Z])/g, ' $1').toLowerCase(),
      v === null || v === undefined || v === '' ? '—' : String(v),
    ]);
  }
}
