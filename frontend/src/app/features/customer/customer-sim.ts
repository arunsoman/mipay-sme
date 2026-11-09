import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MpCard } from '../../design-system/atoms/mp-card';
import { MpSpinner } from '../../design-system/atoms/mp-spinner';

/**
 * CU01-CU03: customer-side simulation - a payer "with a bank or basic phone"
 * pays a merchant QR / short code. This screen deliberately lives outside the
 * merchant portal: it exercises the real public endpoints.
 */
@Component({
  selector: 'customer-sim',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MpCard],
  template: `
    <div class="wrap">
      <mp-card title="Customer payment simulator" subtitle="basic phone / bank app payer (CU01-CU03)">
        <form (ngSubmit)="pay()" class="grid">
          <input class="in" [(ngModel)]="target" name="t" placeholder="QR payload mipay://qr/... or short code MPxxxx" required />
          <input class="in" [(ngModel)]="payer" name="p" placeholder="Your phone number" required />
          <input class="in" [(ngModel)]="amount" name="a" type="number" placeholder="Amount (N$)" required />
          <div class="row">
            <button class="go" type="submit" [disabled]="busy()">Pay now</button>
          </div>
          @if (result()) {
            <div [class]="'res ' + statusClass()" [textContent]="result()"></div>
          }
        </form>
      </mp-card>
    </div>
  `,
  styles: [`
    .wrap { max-width: 430px; margin: 10vh auto 0; padding: var(--sp-4); }
    .grid { display: grid; gap: var(--sp-3); }
    .in { width: 100%; padding: 10px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; }
    .res { font-size: var(--fs-sm); }
    .res.money { color: #16a34a; }
    .res.fail { color: #dc2626; }
    .go { background: #146c94; color: #fff; border: none; border-radius: var(--radius-1); padding: 9px var(--sp-5); font-weight: 600; cursor: pointer; font: inherit; }
  `],
})
export class CustomerSim {
  private readonly http = inject(HttpClient);

  readonly busy = signal(false);
  readonly result = signal('');

  target = '';
  payer = '';
  amount = 60;

  statusClass(): string {
    const r = this.result();
    return r.includes('confirmed') || r.includes('credited') ? 'money' : 'fail';
  }

  async pay() {
    this.busy.set(true);
    this.result.set('');
    try {
      const shortCode = this.target.trim().toUpperCase().startsWith('MP');
      const body = {
        ...(shortCode ? { shortCode: this.target.trim() } : { qrPayload: this.target.trim() }),
        amount: Number(this.amount),
        payerPhone: this.payer,
      };
      const out = await firstValueFrom(this.http.post<Record<string, unknown>>('/api/customer/pay', body));
      this.result.set('Payment confirmed - merchant balance credited at ' + (out['confirmedAt'] ?? 'now'));
    } catch (e) {
      const msg = ((e as { error?: { detail?: string; title?: string } })?.error ?? {});
      this.result.set('Payment failed: ' + (msg['detail'] || msg['title'] || 'unknown'));
    } finally {
      this.busy.set(false);
    }
  }
}
