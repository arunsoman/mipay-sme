import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { KycPayload } from '../../core/api';

/** Read-only rendering of a KYC application document for reviewers. */
@Component({
  selector: 'mpa-kyc-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
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
        <table class="grid">
          <thead><tr><th>Name</th><th>Born</th><th>Nationality</th><th>Residential address</th><th>ID</th><th>Expires</th><th>Owns</th><th>PEP</th></tr></thead>
          <tbody>
            @for (u of d.ubos ?? []; track $index) {
              <tr><td>{{ u['fullLegalName'] }}</td><td>{{ u['dateOfBirth'] ?? '—' }}</td><td>{{ u['nationality'] }}</td><td>{{ u['residentialAddress'] ?? '—' }}</td>
                <td class="mono">{{ u['identificationType'] }} {{ u['identificationNumber'] }}</td>
                <td>{{ u['identificationExpiryDate'] }}</td><td>{{ u['ownershipPercentage'] }}%</td>
                <td>@if (u['politicallyExposedPerson']) {<span class="chip warn small">PEP</span>} @else {no}</td></tr>
            }
          </tbody>
        </table>
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
