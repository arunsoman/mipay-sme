import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { splitList, words } from '../../core/text';
import { MpCard } from '../../../../../src/app/design-system/atoms/mp-card';
import { MpCheckbox } from '../../../../../src/app/design-system/atoms/mp-checkbox';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { MpSpinner } from '../../../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { AccessService } from '../../../web-core/access';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { OrgRightsConfig } from './rights.widget';
import type { OrgRightsNarrowRequest, OrgRightsView } from './rights.contract';

@Component({
  selector: 'mpw-org-rights',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpCheckbox, MpButton, MpSpinner, MpAlert, EmptyState, MpPerm],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="What it may onboard appears here." />
    } @else if (view()) {
      <mp-card title="May onboard" subtitle="Organization types it may bring in itself">
        <div class="list" data-direct>
          @for (t of directAll(); track t) {
            <mp-checkbox [label]="type(t)" [checked]="direct().has(t)" [disabled]="!editable()" (checkedChange)="toggle(direct, t, $event)" />
          } @empty {<span class="dim">Nothing.</span>}
        </div>
      </mp-card>
      <mp-card title="May let them onboard" subtitle="What the organizations it brings in may bring in">
        <div class="list" data-ceiling>
          @for (t of ceilingAll(); track t) {
            <mp-checkbox [label]="type(t)" [checked]="ceiling().has(t)" [disabled]="!editable()" (checkedChange)="toggle(ceiling, t, $event)" />
          } @empty {<span class="dim">Nothing.</span>}
        </div>
      </mp-card>
      <p class="dim">Rights can only be narrowed here; widening them is a new onboarding decision.</p>
      <mp-button *mpPerm="'org.rights:administer'" data-action="narrow" (onClick)="save()">Save</mp-button>
    } @else {
      <mp-spinner label="Loading rights…" />
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; justify-items: stretch; }
    .list { display: grid; gap: var(--sp-1, 4px); }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); margin: 0; }
  `],
})
export class OrgRightsWidget extends MiPayWidget<OrgRightsConfig> {
  private readonly access = inject(AccessService);
  readonly orgId = this.input<string>('orgId');
  readonly view = signal<OrgRightsView | null>(null);
  readonly direct = signal(new Set<string>());
  readonly ceiling = signal(new Set<string>());
  readonly directAll = computed(() => splitList(this.view()?.direct));
  readonly ceilingAll = computed(() => splitList(this.view()?.ceiling));
  readonly editable = computed(() => this.access.can('org.rights:administer'));
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly type = words;

  constructor() {
    super();
    effect(() => {
      const id = this.orgId();
      untracked(() => (id ? this.load(id) : this.state.set('empty')));
    });
  }

  private show(v: OrgRightsView) {
    this.view.set(v);
    this.direct.set(new Set(splitList(v.direct)));
    this.ceiling.set(new Set(splitList(v.ceiling)));
  }

  private load(id: string) {
    this.view.set(null);
    this.data.query<OrgRightsView>('org.rights.get', { id }).subscribe({ next: (v) => this.show(v), error: () => undefined });
  }

  toggle(which: typeof this.direct, t: string, on: boolean) {
    const s = new Set(which());
    if (on) s.add(t); else s.delete(t);
    which.set(s);
  }

  save() {
    const body: OrgRightsNarrowRequest = { rightsDirect: [...this.direct()].sort(), rightsCeiling: [...this.ceiling()].sort() };
    this.data.command<OrgRightsView>('org.rights.narrow', { id: this.orgId(), body }).subscribe({
      next: (v) => { this.show(v); this.flash.set({ kind: 'ok', text: 'Rights saved.' }); },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }
}
