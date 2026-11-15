import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { MpTabs, TabOption } from '../../../../../src/app/design-system/molecules/tabs';
import { MpCheckbox } from '../../../../../src/app/design-system/atoms/mp-checkbox';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { MpSpinner } from '../../../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { AccessService } from '../../../web-core/access';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { OrgAccessConfig } from './access.widget';
import type { OrgFeatures, OrgFeaturesSetRequest } from './access.contract';

const sameSet = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((x) => b.includes(x));

/** Presets on top, one switch per feature, Save. Warnings say what the org can't do. */
@Component({
  selector: 'mpw-org-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpTabs, MpCheckbox, MpButton, MpSpinner, MpAlert, EmptyState, MpPerm],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="Its features appear here." />
    } @else if (view(); as v) {
      @for (w of v.warnings; track w) {<mp-alert kind="bad" data-warning>⚠ {{ w }}</mp-alert>}
      @if (v.applyError) {<p class="dim small">RBAC said: {{ v.applyError }}</p>}

      <div class="presets">
        <span class="lbl">Preset</span>
        <mp-tabs [options]="presetTabs()" [value]="draftPreset()" (valueChange)="usePreset($event)" label="Preset" />
      </div>
      @if (presetHint(); as h) {<p class="dim small">{{ h }}</p>}

      <ul class="features">
        @for (f of v.features; track f.id) {
          <li [attr.data-feature]="f.id">
            <mp-checkbox [label]="f.label" [checked]="draft().includes(f.id)" [disabled]="!canEdit()"
                         (checkedChange)="toggle(f.id, $event)" />
            <span class="dim small">{{ f.screens.join(' · ') }}</span>
            @if (f.partial) {<span class="partial small">only partly given: whoever switched it on does not hold all of it</span>}
          </li>
        }
      </ul>

      <div class="bar" *mpPerm="'org.rights:administer'" data-action="save">
        <span class="dim small">{{ draft().length }} of {{ v.features.length }} features</span>
        <mp-button variant="ghost" [disabled]="!dirty() || saving()" (onClick)="reset()">Undo</mp-button>
        <!-- always on: saving unchanged features applies them in RBAC again (after "Access not applied") -->
        <mp-button [loading]="saving()" (onClick)="save()">{{ dirty() ? 'Save' : 'Apply again' }}</mp-button>
      </div>
      <p class="dim small">The organization's admins hold these through the role <code>{{ v.role }}</code>.
        Roles the organization built itself never keep a switched-off feature.</p>
    } @else if (!flash()) {
      <mp-spinner label="Loading features…" />
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .presets { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
    .lbl { font-weight: 700; font-size: var(--fs-sm, 12.5px); }
    .features { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .features li { display: grid; gap: 2px; }
    .dim { color: var(--mp-text-dim); margin: 0; }
    .small { font-size: var(--fs-xs, 11.5px); }
    .partial { color: var(--mp-amber, #f7c66b); }
    .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
    code { font-family: ui-monospace, Menlo, monospace; }
  `],
})
export class OrgAccessWidget extends MiPayWidget<OrgAccessConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly view = signal<OrgFeatures | null>(null);
  readonly draft = signal<string[]>([]);
  readonly saving = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  /** the switches are read-only without the right to save them (live: grant / revoke) */
  readonly canEdit = computed(() => this.access.cells().has('org.rights:administer'));
  private readonly access = inject(AccessService);

  /** the preset the switches match right now ('custom' when none does) */
  readonly draftPreset = computed(() => this.view()?.presets.find((p) => sameSet(p.features, this.draft()))?.code ?? 'custom');
  readonly presetTabs = computed<TabOption[]>(() => [
    ...(this.view()?.presets ?? []).map((p) => ({ value: p.code, label: p.label, disabled: !this.canEdit() })),
    { value: 'custom', label: 'Custom', disabled: true },
  ]);
  readonly presetHint = computed(() => this.view()?.presets.find((p) => p.code === this.draftPreset())?.description ?? '');
  readonly dirty = computed(() => {
    const v = this.view();
    return !!v && !sameSet(v.features.filter((f) => f.on).map((f) => f.id), this.draft());
  });

  constructor() {
    super();
    effect(() => {
      const id = this.orgId();
      untracked(() => this.load(id));
    });
  }

  private load(id: string | null) {
    this.view.set(null);
    this.flash.set(null);
    if (!id) { this.state.set('empty'); return; }
    this.data.query<OrgFeatures>('org.features.get', { orgId: id }).subscribe({
      next: (v) => this.show(v),
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }

  private show(v: OrgFeatures) {
    this.view.set(v);
    this.draft.set(v.features.filter((f) => f.on).map((f) => f.id));
    this.state.set('ready');
  }

  usePreset(code: string) {
    const p = this.view()?.presets.find((x) => x.code === code);
    if (p) this.draft.set([...p.features]);
  }

  toggle(id: string, on: boolean) {
    this.draft.update((d) => (on ? [...d.filter((x) => x !== id), id] : d.filter((x) => x !== id)));
  }

  reset() {
    const v = this.view();
    if (v) this.draft.set(v.features.filter((f) => f.on).map((f) => f.id));
  }

  save() {
    const v = this.view();
    if (!v) return;
    const preset = this.draftPreset();
    const body: OrgFeaturesSetRequest = { preset: preset === 'custom' ? null : preset, features: this.draft() };
    this.saving.set(true);
    this.data.command<OrgFeatures>('org.features.set', { orgId: v.orgId, body }).subscribe({
      next: (after) => {
        this.saving.set(false);
        this.show(after);
        this.flash.set(after.applyError
          ? { kind: 'bad', text: 'Saved, but RBAC did not apply it yet. See the warning above.' }
          : { kind: 'ok', text: `Saved. ${after.orgName ?? 'The organization'} now has ${after.features.filter((f) => f.on).length} features.` });
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) });
      },
    });
  }
}
