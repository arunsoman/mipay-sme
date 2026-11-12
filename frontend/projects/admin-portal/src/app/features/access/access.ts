import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { OrgAccess } from '../../../../../shared/rbac/org-access';
import { refusal } from '../../../../../shared/kyc/kyc-api';

interface OrgRow { id: string; type: string; name: string; status: string; effectiveStatus: string; depth: number; }

/**
 * MiPay staff: "Roles & access" for any organization (docs/plans/extended-rbac.md ER5):
 * its role ceiling (editable for every org below the platform root), its own roles,
 * and the roles its people hold.
 */
@Component({
  selector: 'mpa-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, OrgAccess],
  template: `
    <div class="head">
      <h2>Roles &amp; access</h2>
      <select name="org" [ngModel]="orgId()" (ngModelChange)="orgId.set($event)">
        @for (o of orgs(); track o.id) {
          <option [value]="o.id">{{ indent(o.depth) }}{{ o.name }} · {{ o.type }} · {{ o.effectiveStatus }}</option>
        }
      </select>
    </div>
    @if (error(); as e) {<div class="err">{{ e }}</div>}
    @if (orgId(); as id) {
      <mp-org-access [orgId]="id" [manage]="true" [canEditCeiling]="id !== rootId()" />
    }
  `,
  styles: [`
    .head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
    select { min-width: 280px; max-width: 100%; }
    .err { color: var(--a-red); font-size: var(--fs-sm); }
  `],
})
export class Access implements OnInit {
  private readonly http = inject(HttpClient);
  readonly orgs = signal<OrgRow[]>([]);
  readonly orgId = signal<string | null>(null);
  readonly rootId = signal<string | null>(null);
  readonly error = signal('');
  readonly indent = (d: number) => '  '.repeat(Math.max(0, d));

  async ngOnInit() {
    try {
      const me = await firstValueFrom(this.http.get<{ id: string; type: string; name: string; effectiveStatus?: string }>('/api/orgs/me'));
      const page = await firstValueFrom(this.http.get<{ content: OrgRow[] }>('/api/orgs/me/descendants?size=500'));
      this.rootId.set(me.id);
      this.orgs.set([{ id: me.id, type: me.type, name: me.name, status: '', effectiveStatus: me.effectiveStatus ?? 'ACTIVE', depth: 0 },
        ...page.content.filter((o) => o.id !== me.id)]);
      this.orgId.set(this.orgs().length > 1 ? this.orgs()[1].id : me.id);
    } catch (e) {
      this.error.set(refusal(e));
    }
  }
}
