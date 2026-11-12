import { ChangeDetectionStrategy, ChangeDetectorRef, Component, computed, effect, inject, input, OnInit, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Doc, evaluateCondition } from './kyc-conditions';

/**
 * Schema-driven KYC form, shared by every portal. Every input comes from the
 * resolved master profile (GET /api/kyc/schema/{orgId}): OBJECT sections
 * render one card, LIST sections repeatable cards within min/max bounds,
 * choice fields bind master lists (inline or /api/kyc/lists/{code}) with
 * parent chaining (bank → branch). visibleWhen / requiredWhen run in the
 * browser with the same grammar the server uses, so hidden fields disappear
 * and conditional fields get their * as the user types. Server problems
 * ("ubos[0].dateOfBirth: …") are shown under the field they name.
 * The document is mutated in place; the host saves it.
 */
export interface SchemaField {
  key: string; label: string; helpText: string | null; dataType: string;
  listCode: string | null; listParentField: string | null; required: string;
  requiredWhen: unknown; visibleWhen: unknown; pattern: string | null;
  minLength: number | null; maxLength: number | null; minValue: number | null; maxValue: number | null;
  derivedFrom?: string | null; canonicalTag?: string | null; sensitivity: string; sensitive: boolean;
}

export interface SchemaSection {
  key: string; title: string; kind: 'OBJECT' | 'LIST' | 'DOCUMENTS'; step?: number;
  minItems: number | null; maxItems: number | null; visible: boolean;
  fields: SchemaField[];
}

export interface SchemaResponse {
  profile: { code: string; country: string; licenceType: string };
  schema: { code: string; version: number };
  sections: SchemaSection[];
  rules: { code: string; phase: string; validator: string; message: string }[];
  submitRules: { code: string; phase: string; validator: string; message: string }[];
  lists: Record<string, ListItem[]>;
  /** Profile tunables, e.g. uboThresholdPct (beneficial-owner threshold). */
  params?: Record<string, unknown>;
}

/** A list item; `attributes.chips` turns a MULTI_ENUM into "involvement" chips (role lists). */
type ListItem = { value: string; label: string; attributes?: { chips?: Record<string, string>; more?: boolean; owner?: boolean } };
type Chip = { value: string; label: string; more: boolean };

@Component({
  selector: 'mp-kyc-schema-editor',
  changeDetection: ChangeDetectionStrategy.Default,
  imports: [FormsModule, NgTemplateOutlet],
  template: `
    @if (generalProblems().length) {
      <div class="probs">@for (p of generalProblems(); track p) {<div>• {{ p }}</div>}</div>
    }
    @if (!disabled()) {
    <nav class="steps" aria-label="KYC steps">
      @for (st of steps(); track st.no; let i = $index) {
        @let st2 = stepState(st.keys);
        <button type="button" class="step" [class.cur]="!showAll() && i === stepIdx()"
                [class.bad]="st2.problems > 0" [class.done]="st2.problems === 0 && st2.missing === 0"
                (click)="goto(i)">
          <span class="num">{{ st2.problems === 0 && st2.missing === 0 ? '✓' : i + 1 }}</span>
          <span class="st">{{ st.title }}</span>
          @if (st2.problems > 0) {<span class="cnt bad">{{ st2.problems }}</span>}
          @else if (st2.missing > 0 && !disabled()) {<span class="cnt">{{ st2.missing }}</span>}
        </button>
      }
      <button type="button" class="step all" [class.cur]="showAll()" (click)="showAll.set(!showAll())">
        {{ showAll() ? 'One step at a time' : 'Show all' }}</button>
    </nav>
    <div class="legend">a number on a step = required fields still empty · red = problems from the last save</div>
    }

    @for (sec of shownSections(); track sec.key) {
      <fieldset class="sec" [disabled]="disabled()">
        <legend>{{ sec.title }}
          @if (sec.kind === 'LIST') {<span class="muted">{{ minMax(sec) }}</span>}
        </legend>
        @if (problemsAt(sec.key); as ps) {@for (p of ps; track p) {<div class="fp">{{ p }}</div>}}
        @if (isOwnershipSection(sec) && needsController(sec)) {
          <div class="callout">No one owns {{ threshold() }}% or more. Also add the person who controls the business —
            or, failing that, its most senior manager — and tick <b>Runs the business day to day</b> or
            <b>Controls it another way</b> for them.</div>
        }

        @if (sec.kind === 'OBJECT') {
          @let item = docItem(sec);
          @if (item) {
            <div class="f">
              @for (fl of sec.fields; track fl.key) {
                @if (visible(fl, item)) {
                  <ng-container [ngTemplateOutlet]="fieldT"
                    [ngTemplateOutletContext]="{ fl: fl, item: item, path: sec.key + '.' + fl.key }" />
                }
              }
            </div>
          }
        } @else {
          @for (item of docList(sec); track $index; let i = $index) {
            <div class="item">
              <div class="ihead"><span>{{ singular(sec) }} {{ i + 1 }}
                @if (isOwnershipSection(sec) && isBeneficialOwner(item)) {
                  <span class="bo" title="Owns or controls at least {{ threshold() }}% — identified as a beneficial owner">✓ Beneficial owner</span>
                }</span>
                @if (!disabled() && canRemove(sec, docList(sec).length)) {
                  <button type="button" class="ghost rm" (click)="removeItem(sec, i)">Remove</button>
                }
              </div>
              <div class="f">
                @for (fl of sec.fields; track fl.key) {
                  @if (visible(fl, item)) {
                    <ng-container [ngTemplateOutlet]="fieldT"
                      [ngTemplateOutletContext]="{ fl: fl, item: item, path: sec.key + '[' + i + '].' + fl.key }" />
                  }
                }
              </div>
            </div>
          }
          @if (!disabled() && canAdd(sec, docList(sec).length)) {
            <button type="button" class="ghost add" (click)="addItem(sec)">+ {{ singular(sec) }}</button>
          }
          @if (sumField(sec); as sf) {
            <div class="muted tiny">Total {{ sf.label.toLowerCase() }}: {{ totalFor(sec, sf.key) }}%</div>
          }
        }
      </fieldset>
    }
    @if (!disabled() && !showAll() && steps().length > 1) {
      <div class="pager">
        <button type="button" class="ghost" [disabled]="stepIdx() === 0" (click)="goto(stepIdx() - 1)">‹ Back</button>
        <span class="muted tiny">step {{ stepIdx() + 1 }} of {{ steps().length }}</span>
        <button type="button" [disabled]="stepIdx() === steps().length - 1" (click)="goto(stepIdx() + 1)">Next ›</button>
      </div>
    }

    <ng-template #fieldT let-fl="fl" let-item="item" let-path="path">
      <div class="fl" [class.bad]="problemsAt(path)" [class.wide]="fl.dataType === 'MULTI_ENUM'">
        @if (fl.dataType === 'BOOLEAN') {
          <label class="chk">
            <input type="checkbox" [ngModel]="item[fl.key] === true" [ngModelOptions]="o"
                   (ngModelChange)="set(item, fl, $event)" /> {{ star(fl, item) }}{{ fl.label }}</label>
        } @else if (fl.dataType === 'MULTI_ENUM' && hasChips(fl)) {
          <span class="lb">{{ star(fl, item) }}{{ fl.label }}</span>
          @if (!entityType()) {<span class="tiny">Choose the entity type in step 1 to see the right choices.</span>}
          <span class="chips">
            @for (c of chipsFor(fl, false); track c.value) {
              <button type="button" class="chipbtn" [class.on]="multiHas(item, fl, c.value)"
                      [attr.aria-pressed]="multiHas(item, fl, c.value)"
                      (click)="chipToggle(item, fl, c.value)">{{ multiHas(item, fl, c.value) ? '✓ ' : '' }}{{ c.label }}</button>
            }
            @if (chipsFor(fl, true).length) {
              @if (moreOpen.has(item) || hasMoreSelected(item, fl)) {
                @for (c of chipsFor(fl, true); track c.value) {
                  <button type="button" class="chipbtn more" [class.on]="multiHas(item, fl, c.value)"
                          (click)="chipToggle(item, fl, c.value)">{{ multiHas(item, fl, c.value) ? '✓ ' : '' }}{{ c.label }}</button>
                }
              } @else {
                <button type="button" class="linkbtn" (click)="moreOpen.add(item)">More roles…</button>
              }
            }
          </span>
          @if (multiHas(item, fl, 'NOMINEE') && !anyHas(fl, 'NOMINATOR')) {
            <span class="callout">They hold the share for someone else — we also need that person.
              <button type="button" class="linkbtn" (click)="addNominator(fl)">＋ Add the person they hold it for</button></span>
          }
        } @else if (fl.dataType === 'MULTI_ENUM') {
          <span class="lb">{{ star(fl, item) }}{{ fl.label }}</span>
          <span class="grp">
            @for (o2 of listOf(fl, item); track o2.value) {
              <label class="chk"><input type="checkbox" class="multi"
                [checked]="multiHas(item, fl, o2.value)"
                (change)="multiToggle(item, fl, o2.value, $event)" /> {{ o2.label || o2.value }}</label>
            }
          </span>
        } @else if (fl.listCode) {
          <span class="lb">{{ star(fl, item) }}{{ fl.label }}</span>
          <select class="inp" [ngModel]="item[fl.key]" [ngModelOptions]="o"
                  [disabled]="isDependentPending(fl, item) || isDerivedLocked(fl, item)"
                  (ngModelChange)="set(item, fl, $event); onPicked(fl, item, $event)">
            <option [ngValue]="null">—</option>
            @for (o2 of listOf(fl, item); track o2.value) {<option [ngValue]="o2.value">{{ o2.label || o2.value }}</option>}
          </select>
          @if (isDependentPending(fl, item)) {<span class="tiny">choose {{ parentLabel(fl) }} first</span>}
          @if (isDerivedLocked(fl, item)) {<span class="tiny">set from {{ parentLabel(fl, fl.derivedFrom) }}</span>}
        } @else if (fl.dataType === 'DATE') {
          <span class="lb">{{ star(fl, item) }}{{ fl.label }}</span>
          <input class="inp" type="date" [ngModel]="item[fl.key]" [ngModelOptions]="o"
                 (ngModelChange)="set(item, fl, $event)" />
        } @else if (isNumeric(fl.dataType)) {
          <span class="lb">{{ star(fl, item) }}{{ fl.label }}{{ fl.dataType === 'PERCENT' && !fl.label.includes('%') ? ' (%)' : '' }}</span>
          <input class="inp" type="number" [attr.min]="fl.minValue" [attr.max]="fl.maxValue" step="any"
                 [ngModel]="item[fl.key]" [ngModelOptions]="o" (ngModelChange)="set(item, fl, $event)" />
        } @else {
          <span class="lb">{{ star(fl, item) }}{{ fl.label }}</span>
          <input class="inp" [attr.maxlength]="fl.maxLength ?? 200" [attr.type]="inputType(fl)"
                 [attr.autocomplete]="fl.sensitive ? 'off' : null"
                 [ngModel]="item[fl.key]" [ngModelOptions]="o" (ngModelChange)="set(item, fl, $event)" />
        }
        @if (fl.helpText) {<span class="tiny">{{ fl.helpText }}</span>}
        @for (p of problemsAt(path) ?? []; track p) {<span class="fp">{{ p }}</span>}
      </div>
    </ng-template>
  `,
  styles: [`
    :host { display: grid; gap: 12px; min-width: 0; }
    .steps { display: flex; gap: 6px; flex-wrap: wrap; }
    .step { display: inline-flex; align-items: center; gap: 6px; padding: 5px 10px; border-radius: 999px;
            background: transparent; border: 1px solid var(--a-border); color: var(--a-dim); font-size: var(--fs-sm); }
    .step .num { display: inline-grid; place-items: center; width: 18px; height: 18px; border-radius: 50%;
                 background: var(--a-panel-2); font-size: 10.5px; }
    .step.cur { border-color: var(--a-teal); color: var(--a-text); }
    .step.cur .num { background: var(--a-teal); color: var(--a-navy); }
    .step.done .num { color: var(--a-green); }
    .step.cur.done .num { color: var(--a-navy); background: var(--a-green); }
    .step.bad { border-color: color-mix(in srgb, var(--a-red) 60%, transparent); }
    .step .cnt { font-size: 10px; padding: 0 6px; border-radius: 999px; background: var(--a-panel-2); color: var(--a-amber); }
    .step .cnt.bad { color: var(--a-red); }
    .step.all { margin-left: auto; border-style: dashed; }
    .legend { font-size: 10.5px; color: var(--a-dim); margin-top: -6px; }
    .pager { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    @media (max-width: 600px) { .step .st { display: none; } .step.cur .st { display: inline; } .step.all { margin-left: 0; } }
    .sec { border: 1px dashed var(--a-border); border-radius: 10px; padding: 10px 12px 12px; margin: 0; display: grid; gap: 8px; min-width: 0; }
    legend { font-size: 13px; font-weight: 600; padding: 0 6px; }
    legend .muted { font-weight: 400; font-size: var(--fs-xs); color: var(--a-dim); margin-left: 6px; }
    .f { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 10px; }
    .fl { display: grid; gap: 4px; font-size: var(--fs-sm); color: var(--a-dim); min-width: 0; align-content: start; }
    .fl.bad .inp { border-color: var(--a-red); }
    .fl.wide { grid-column: 1 / -1; }
    .lb { font-size: var(--fs-xs); }
    .chk { display: flex; align-items: center; gap: 6px; }
    .chk input, input.multi { width: auto; }
    .grp { display: flex; flex-wrap: wrap; gap: 4px 12px; }
    .item { border: 1px solid var(--a-border); border-radius: 8px; padding: 10px; display: grid; gap: 8px; }
    .ihead { display: flex; align-items: center; justify-content: space-between; font-size: var(--fs-xs); color: var(--a-dim); }
    .rm { padding: 3px 10px; }
    .add { justify-self: start; }
    .inp { box-sizing: border-box; height: 34px; width: 100%; }
    select.inp { height: 36px; }
    .tiny { font-size: 10.5px; color: var(--a-dim); }
    .fp { font-size: 11px; color: var(--a-red); }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chipbtn { padding: 6px 12px; font-size: 12.5px; font-weight: 600; border-radius: 999px;
               border: 1px solid var(--a-border-strong, #444); background: #151515; color: var(--a-text); }
    .chipbtn.on { background: color-mix(in srgb, var(--a-teal) 18%, transparent); border-color: var(--a-teal); color: var(--a-teal); }
    .chipbtn.more { border-style: dashed; }
    .linkbtn { background: none; border: none; padding: 4px 6px; color: var(--a-teal); font-size: 12px; cursor: pointer; }
    .callout { display: block; font-size: 12px; padding: 8px 10px; border-radius: 10px; color: var(--a-amber, #f7c66b);
               background: color-mix(in srgb, var(--a-teal) 10%, transparent); }
    .bo { margin-left: 8px; font-size: 10.5px; font-weight: 800; letter-spacing: .04em; text-transform: uppercase;
          color: var(--a-green); background: var(--a-green-bg, #173a29); padding: 2px 8px; border-radius: 999px; }
    .probs { font-size: var(--fs-sm); color: var(--a-red); display: grid; gap: 2px; }
  `],
})
export class KycSchemaEditor implements OnInit {
  readonly schema = input.required<SchemaResponse>();
  readonly doc = input.required<Doc>();
  readonly disabled = input(false);
  /** Condition context: orgType, country, licenceType. */
  readonly context = input<Doc>({});
  /** Server problems, each "path: message" (or a bare message). */
  readonly problems = input<string[]>([]);
  readonly entityTypeChange = output<string | null>();
  readonly o = { standalone: true };

  private readonly http = inject(HttpClient);
  /** Lists arrive asynchronously; hosts are OnPush, so re-render explicitly. */
  private readonly cdr = inject(ChangeDetectorRef);
  /** `${listCode}` for parent-less lists; `${listCode}=${parent}` for dependent ones. */
  private readonly loaded = new Map<string, ListItem[]>();
  readonly sections = computed(() => this.schema().sections.filter((s) => s.visible !== false && s.kind !== 'DOCUMENTS'
    && (s.kind === 'LIST' || s.fields.length > 0)));

  /** Wizard steps: sections sharing a master `step` number appear together. */
  readonly steps = computed(() => {
    const out: { no: number; title: string; keys: string[] }[] = [];
    this.sections().forEach((s, i) => {
      const no = s.step ?? i + 1;
      const hit = out.find((x) => x.no === no);
      if (hit) { hit.keys.push(s.key); hit.title += ' · ' + s.title; }
      else out.push({ no, title: s.title, keys: [s.key] });
    });
    return out;
  });
  readonly stepIdx = signal(0);
  readonly showAll = signal(false);
  readonly shownSections = computed(() => {
    if (this.showAll() || this.disabled()) return this.sections();
    const st = this.steps()[Math.min(this.stepIdx(), this.steps().length - 1)];
    return st ? this.sections().filter((s) => st.keys.includes(s.key)) : this.sections();
  });

  goto(i: number) {
    this.showAll.set(false);
    this.stepIdx.set(Math.max(0, Math.min(i, this.steps().length - 1)));
  }

  /** Per step: server problems on its sections, and required fields still empty. */
  stepState(keys: string[]) {
    let problems = 0;
    for (const [path, msgs] of this.byPath()) {
      if (path && keys.includes(path.split(/[.[]/)[0])) problems += msgs.length;
    }
    let missing = 0;
    for (const sec of this.sections().filter((s) => keys.includes(s.key))) {
      const rows = sec.kind === 'LIST' ? this.docList(sec) : [this.docItem(sec)].filter(Boolean) as Doc[];
      if (sec.kind === 'LIST' && rows.length < (sec.minItems ?? 0)) missing += (sec.minItems ?? 0) - rows.length;
      for (const row of rows) {
        for (const fl of sec.fields) {
          if (!this.visible(fl, row) || !this.requiredNow(fl, row) || this.isServerDerived(fl)) continue;
          const v = row[fl.key];
          if (v === null || v === undefined || v === '' || (Array.isArray(v) && v.length === 0)) missing++;
        }
      }
    }
    return { problems, missing };
  }

  private readonly byPath = computed(() => {
    const m = new Map<string, string[]>();
    for (const p of this.problems()) {
      const i = p.indexOf(': ');
      const path = i > 0 && !p.slice(0, i).includes(' ') ? p.slice(0, i) : '';
      const msg = path ? p.slice(i + 2) : p;
      m.set(path, [...(m.get(path) ?? []), msg]);
    }
    return m;
  });
  readonly generalProblems = computed(() => {
    const known = new Set<string>();
    for (const s of this.sections()) known.add(s.key);
    return [...this.byPath().entries()]
      .filter(([path]) => !path || !this.matchesSomeField(path, known))
      .flatMap(([path, msgs]) => msgs.map((m) => (path ? `${path}: ${m}` : m)));
  });

  constructor() {
    effect(() => {
      const probs = this.problems();
      if (!probs.length) return;
      untracked(() => {
        const i = this.steps().findIndex((st) => this.stepState(st.keys).problems > 0);
        if (i >= 0) this.goto(i);
      });
    });
  }

  async ngOnInit() {
    // skeleton: every visible section exists — lists start with their minimum, objects with nulls
    const codes = new Set<string>();
    for (const sec of this.sections()) {
      if (sec.kind === 'LIST') {
        if (!Array.isArray(this.doc()[sec.key])) this.doc()[sec.key] = [];
        const arr = this.doc()[sec.key] as Doc[];
        if (!this.disabled()) while (arr.length < (sec.minItems ?? 0)) arr.push(this.freshItem(sec));
      } else {
        const existing = this.doc()[sec.key];
        const item = existing && typeof existing === 'object' && !Array.isArray(existing)
          ? (existing as Doc) : (this.doc()[sec.key] = {} as Doc) as Doc;
        for (const fl of sec.fields) item[fl.key] ??= this.defaultFor(fl);
      }
      for (const fl of sec.fields) if (fl.listCode) codes.add(fl.listCode);
    }
    for (const code of codes) {
      if (this.schema().lists[code]) continue;
      const parented = this.sections().some((s) => s.fields.some((f) => f.listCode === code && f.listParentField));
      try {
        const r = await firstValueFrom(this.http.get<{ items: ListItem[] }>(`/api/kyc/lists/${code}`));
        this.loaded.set(code, r.items);
        this.cdr.markForCheck();
      } catch { /* offered once a parent is picked */ }
      if (parented) await this.preloadDependents(code);
    }
    this.applyEntityType();
    this.cdr.markForCheck();
  }

  /** Existing rows already have a parent value (e.g. bankCode): load their branch lists. */
  private async preloadDependents(code: string) {
    for (const sec of this.sections()) {
      for (const fl of sec.fields.filter((f) => f.listCode === code && f.listParentField)) {
        const rows = sec.kind === 'LIST' ? this.docList(sec) : [this.docItem(sec)].filter(Boolean) as Doc[];
        for (const row of rows) {
          const pv = row[fl.listParentField!];
          if (pv != null) await this.loadDependent(code, String(pv));
        }
      }
    }
  }

  private async loadDependent(code: string, parent: string) {
    const key = `${code}=${parent}`;
    if (this.loaded.has(key)) return;
    try {
      const r = await firstValueFrom(this.http.get<{ items: ListItem[] }>(
        `/api/kyc/lists/${code}`, { params: { parent } }));
      this.loaded.set(key, r.items);
      this.cdr.markForCheck();
    } catch { /* unknown code */ }
  }

  // ------------------------------------------------------------------ conditions

  visible(fl: SchemaField, item: Doc) {
    return evaluateCondition(fl.visibleWhen, this.doc(), item, this.context());
  }

  requiredNow(fl: SchemaField, item: Doc) {
    if (fl.required === 'ALWAYS') return true;
    if (fl.required !== 'WHEN') return false;
    return evaluateCondition(fl.requiredWhen, this.doc(), item, this.context());
  }

  star(fl: SchemaField, item: Doc) {
    return this.requiredNow(fl, item) ? '* ' : '';
  }

  // ------------------------------------------------------------------ problems

  problemsAt(path: string): string[] | null {
    return this.byPath().get(path) ?? null;
  }

  private matchesSomeField(path: string, sections: Set<string>) {
    const sec = path.split(/[.[]/)[0];
    return sections.has(sec);
  }

  // ------------------------------------------------------------------ document accessors

  docItem(sec: SchemaSection): Doc | null {
    const v = this.doc()[sec.key];
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Doc) : null;
  }

  docList(sec: SchemaSection): Doc[] {
    const v = this.doc()[sec.key];
    return Array.isArray(v) ? (v as Doc[]) : [];
  }

  freshItem(sec: SchemaSection): Doc {
    const item: Doc = {};
    for (const fl of sec.fields) item[fl.key] = this.defaultFor(fl);
    if ('politicallyExposedPerson' in item) item['politicallyExposedPerson'] = false;
    return item;
  }

  /** Country fields start at the profile's country (NA); everything else empty. */
  private defaultFor(fl: SchemaField): unknown {
    if (this.disabled()) return null;
    return fl.dataType === 'COUNTRY' ? (this.context()['country'] ?? 'NA') : null;
  }

  addItem(sec: SchemaSection) { this.docList(sec).push(this.freshItem(sec)); }

  removeItem(sec: SchemaSection, i: number) {
    const arr = this.docList(sec);
    if (arr.length > (sec.minItems ?? 0)) arr.splice(i, 1);
  }

  canAdd(sec: SchemaSection, n: number) { return sec.maxItems == null || n < sec.maxItems; }
  canRemove(sec: SchemaSection, n: number) { return n > (sec.minItems ?? 0); }

  singular(sec: SchemaSection) {
    if (this.isOwnershipSection(sec)) return 'Person';
    const t = sec.title.split(':')[0].replace(/ and controllers$/i, '');
    return t.endsWith('es') && /sses$/i.test(t) ? t.slice(0, -2) : t.replace(/s$/, '');
  }

  minMax(sec: SchemaSection) {
    const min = sec.minItems ?? 0;
    return sec.maxItems == null ? `at least ${min}`
      : min === sec.maxItems ? `exactly ${min}` : `${min}–${sec.maxItems}`;
  }

  // ------------------------------------------------------------------ people: involvement chips

  /** Items whose "…more roles" are expanded. */
  readonly moreOpen = new WeakSet<Doc>();

  entityType(): string | null {
    const bp = this.doc()['businessProfile'];
    const v = bp && typeof bp === 'object' ? (bp as Doc)['entityType'] : null;
    return typeof v === 'string' && v ? v : null;
  }

  threshold(): number {
    const v = this.schema().params?.['uboThresholdPct'];
    return typeof v === 'number' ? v : 25;
  }

  private items(fl: SchemaField): ListItem[] {
    return (fl.listCode && (this.schema().lists[fl.listCode] ?? this.loaded.get(fl.listCode))) || [];
  }

  hasChips(fl: SchemaField) {
    return this.items(fl).some((i) => !!i.attributes?.chips);
  }

  /** The chips this entity type offers; without an entity type, the generic ("*") ones. */
  chipsFor(fl: SchemaField, more: boolean): Chip[] {
    const et = this.entityType();
    const out: Chip[] = [];
    for (const i of this.items(fl)) {
      const chips = i.attributes?.chips;
      if (!chips || !!i.attributes?.more !== more) continue;
      const label = (et && chips[et]) || chips['*'];
      if (label) out.push({ value: i.value, label, more });
    }
    return out;
  }

  hasMoreSelected(item: Doc, fl: SchemaField) {
    return this.chipsFor(fl, true).some((c) => this.multiHas(item, fl, c.value));
  }

  chipToggle(item: Doc, fl: SchemaField, value: string) {
    const cur = Array.isArray(item[fl.key]) ? (item[fl.key] as string[]).slice() : [];
    const i = cur.indexOf(value);
    if (i >= 0) cur.splice(i, 1); else cur.push(value);
    item[fl.key] = cur;
  }

  anyHas(fl: SchemaField, value: string) {
    const sec = this.sections().find((s) => s.fields.includes(fl));
    return !!sec && this.docList(sec).some((it) => this.multiHas(it, fl, value));
  }

  addNominator(fl: SchemaField) {
    const sec = this.sections().find((s) => s.fields.includes(fl));
    if (!sec || !this.canAdd(sec, this.docList(sec).length)) return;
    const it = this.freshItem(sec);
    it[fl.key] = ['NOMINATOR'];
    this.docList(sec).push(it);
  }

  /** The section that carries the ownership tag (the people section). */
  isOwnershipSection(sec: SchemaSection) {
    return sec.kind === 'LIST' && sec.fields.some((f) => f.canonicalTag === 'UBO_OWNERSHIP');
  }

  private pct(v: unknown) { const n = Number(v); return Number.isFinite(n) ? n : 0; }

  isBeneficialOwner(item: Doc) {
    const share = Math.max(this.pct(item['ownershipPercentage']), this.pct(item['effectiveOwnershipPercentage']),
      this.pct(item['votingRightsPercentage']));
    return share >= this.threshold();
  }

  /** Owners are listed but nobody reaches the threshold and no controller is named yet. */
  needsController(sec: SchemaSection) {
    const et = this.entityType();
    if (!et || et === 'TRUST' || et === 'SOLE_PROPRIETOR') return false;
    const people = this.docList(sec);
    const anyShare = people.some((p) => this.pct(p['ownershipPercentage']) > 0);
    if (!anyShare || people.some((p) => this.isBeneficialOwner(p))) return false;
    return !people.some((p) => Array.isArray(p['roles'])
      && ((p['roles'] as string[]).includes('CONTROLLING_PERSON') || (p['roles'] as string[]).includes('SENIOR_MANAGER')));
  }

  /**
   * Entity type changed: drop roles that this entity type doesn't offer, and for a sole
   * proprietor pre-fill the single owner (Owner, 100%).
   */
  private applyEntityType() {
    const sec = this.sections().find((s) => this.isOwnershipSection(s));
    const rolesField = sec?.fields.find((f) => f.dataType === 'MULTI_ENUM' && this.hasChips(f));
    if (!sec || !rolesField || this.disabled()) return;
    const allowed = new Set([...this.chipsFor(rolesField, false), ...this.chipsFor(rolesField, true)].map((c) => c.value));
    for (const p of this.docList(sec)) {
      if (Array.isArray(p[rolesField.key])) p[rolesField.key] = (p[rolesField.key] as string[]).filter((r) => allowed.has(r));
    }
    if (this.entityType() === 'SOLE_PROPRIETOR') {
      if (!this.docList(sec).length) this.docList(sec).push(this.freshItem(sec));
      const owner = this.docList(sec)[0];
      const roles = Array.isArray(owner[rolesField.key]) ? owner[rolesField.key] as string[] : [];
      if (!roles.includes('PROPRIETOR')) owner[rolesField.key] = ['PROPRIETOR', ...roles];
      owner['ownershipPercentage'] = 100;   // a sole proprietor owns all of it (rule SOLE_PROPRIETOR_HUNDRED_PCT)
    }
  }

  // ------------------------------------------------------------------ field helpers

  set(item: Doc, fl: SchemaField, value: unknown) {
    item[fl.key] = value === '' ? null : value;
    if (fl.key === 'entityType') {
      this.applyEntityType();
      this.entityTypeChange.emit((value as string) ?? null);
    }
  }

  isNumeric(dt: string) { return ['NUMBER', 'MONEY', 'PERCENT'].includes(dt); }

  inputType(fl: SchemaField) {
    switch (fl.dataType) {
      case 'URL': return 'url';
      case 'EMAIL': return 'email';
      case 'PHONE': return 'tel';
      default: return 'text';
    }
  }

  /** Options: inline list > fetched list > the dependent list for this row's parent value. */
  listOf(fl: SchemaField, item: Doc): ListItem[] {
    if (!fl.listCode) return [];
    const inline = this.schema().lists[fl.listCode];
    if (inline) return inline;
    if (fl.listParentField) {
      const pv = item[fl.listParentField];
      return pv == null ? [] : this.loaded.get(`${fl.listCode}=${pv}`) ?? [];
    }
    return this.loaded.get(fl.listCode) ?? [];
  }

  isDependentPending(fl: SchemaField, item: Doc) {
    return !!(fl.listParentField && !this.schema().lists[fl.listCode!] && item[fl.listParentField] == null);
  }

  /**
   * A derived field (industryVertical ← mcc) is filled by the server. A field whose
   * derivedFrom is also its list parent (branch ← bankCode) only records lineage: it is
   * a cascading pick the user makes, so it must stay editable.
   */
  isServerDerived(fl: SchemaField) {
    return !!fl.derivedFrom && fl.derivedFrom !== fl.listParentField;
  }

  /** A server-derived field is locked once its source is set. */
  isDerivedLocked(fl: SchemaField, item: Doc) {
    return this.isServerDerived(fl) && item[fl.derivedFrom!] != null && item[fl.derivedFrom!] !== '';
  }

  parentLabel(fl: SchemaField, key: string | null | undefined = fl.listParentField) {
    const parent = this.sections().flatMap((s) => s.fields).find((f) => f.key === key);
    return parent?.label.toLowerCase() ?? key ?? '';
  }

  /** After picking a value that parents another list, fetch that list for the row. */
  async onPicked(fl: SchemaField, item: Doc, value: unknown) {
    for (const sec of this.sections()) {
      for (const other of sec.fields) {
        if (other.listParentField !== fl.key) continue;
        item[other.key] = null;
        if (other.listCode && value != null) await this.loadDependent(other.listCode, String(value));
      }
    }
  }

  multiHas(item: Doc, fl: SchemaField, value: string) {
    const arr = item[fl.key];
    return Array.isArray(arr) && arr.includes(value);
  }

  multiToggle(item: Doc, fl: SchemaField, value: string, ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    const cur = Array.isArray(item[fl.key]) ? (item[fl.key] as string[]).slice() : [];
    const i = cur.indexOf(value);
    if (on && i < 0) cur.push(value);
    if (!on && i >= 0) cur.splice(i, 1);
    item[fl.key] = cur;
  }

  sumField(sec: SchemaSection): SchemaField | null {
    return sec.fields.find((f) => f.dataType === 'PERCENT' && f.key === 'ownershipPercentage') ?? null;
  }

  totalFor(sec: SchemaSection, fieldKey: string) {
    return this.docList(sec).reduce((sum, item) => sum + (Number(item[fieldKey]) || 0), 0);
  }
}
