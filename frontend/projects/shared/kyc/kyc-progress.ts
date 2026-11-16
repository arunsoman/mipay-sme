import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/** The KYC tab title: the section title exactly as the master (and kyc-filed.md) words it. */
export function shortStepLabel(sec: { key: string; title: string } | undefined): string {
  return sec?.title ?? '';
}

/** The five KYC tabs, for screens drawn before the schema is loaded (kyc-filed.md). */
export const KYC_STEP_LABELS = [
  'Business Profile & General Information',
  'Operational & Store Details',
  'Registered & Operational Address',
  'Ultimate Beneficial Owners (UBO) & Key Management',
  'Settlement & Payout Bank Account Details',
];

/**
 * The KYC tabs: one thin bar per section with its title underneath; any tab can be picked.
 * Current = accent, done = ✓, problems = red. On a phone only the current name shows.
 */
@Component({
  selector: 'mp-kyc-progress',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="prog" [style.--n]="labels().length" aria-label="KYC steps">
      @for (l of labels(); track $index; let i = $index) {
        <li [class.cur]="i === current()" [class.done]="done()[i] && i !== current()" [class.bad]="bad()[i]"
            [attr.aria-current]="i === current() ? 'step' : null">
          <button type="button" [disabled]="!clickable()" (click)="pick.emit(i)">
            <span class="bar"></span>
            <span class="name">@if (done()[i] && !bad()[i]) {<span class="tick">✓</span>}{{ l }}</span>
          </button>
        </li>
      }
    </ol>
    <div class="phone">Step {{ current() + 1 }} of {{ labels().length }} · <b>{{ labels()[current()] }}</b></div>
  `,
  styles: [`
    :host { display: block; }
    .prog { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(var(--n), minmax(0, 1fr)); gap: 8px; }
    button { all: unset; display: grid; gap: 8px; width: 100%; cursor: pointer; }
    button:disabled { cursor: default; }
    button:focus-visible .bar { outline: 2px solid var(--a-teal); outline-offset: 2px; }
    .bar { height: 4px; border-radius: 4px; background: #262626; transition: background .2s; }
    .name { font-size: 12.5px; line-height: 1.35; color: #7a7a7a; }
    .tick { color: var(--a-green); margin-right: 5px; }
    .done .bar { background: color-mix(in srgb, var(--a-teal) 45%, #262626); }
    .done .name { color: var(--a-dim); }
    .cur .bar { background: var(--a-teal); }
    .cur .name { color: var(--a-text); font-weight: 600; }
    .bad .bar { background: var(--a-red); }
    .phone { display: none; font-size: 13px; color: var(--a-dim); margin-top: 10px; }
    .phone b { color: var(--a-text); font-weight: 600; }
    @media (max-width: 640px) { .name { display: none; } .phone { display: block; } }
  `],
})
export class KycProgress {
  readonly labels = input.required<string[]>();
  readonly current = input(0);
  readonly done = input<boolean[]>([]);
  readonly bad = input<boolean[]>([]);
  readonly clickable = input(false);
  readonly pick = output<number>();
}
