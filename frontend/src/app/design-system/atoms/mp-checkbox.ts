import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/**
 * Atom: THE checkbox. A real native checkbox (keyboard, screen readers, forms) drawn the same in
 * every portal: ticked, unticked, disabled (greyed, not clickable) and `locked` (ticked and
 * not changeable here, e.g. a cell inherited from another role) and `readonly` (shown exactly as it
 * is - ticked or not - but not changeable, e.g. a role viewed rather than edited). Every tick box goes through
 * mp-checkbox, never a bare <input type="checkbox">.
 *
 * <mp-checkbox [(checked)]="agree" label="I agree" />
 * <mp-checkbox [checked]="on" [disabled]="!supported" ariaLabel="Create on Org users" (checkedChange)="set($event)" />
 */
@Component({
  selector: 'mp-checkbox',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="cb" [class.dis]="disabled()" [class.locked]="locked()" [class.ro]="readonly()" [attr.title]="title() || null">
      <input type="checkbox" [checked]="checked()" [disabled]="disabled() || locked() || readonly()"
             [attr.aria-label]="ariaLabel() || null" (change)="onChange($event)" />
      <span class="box" aria-hidden="true"></span>
      @if (label()) {<span class="lbl">{{ label() }}</span>}
    </label>
  `,
  styles: [`
    :host { display: inline-flex; }
    .cb { position: relative; display: inline-flex; align-items: center; gap: var(--sp-2, 8px); cursor: pointer; min-height: 24px; }
    .cb.dis, .cb.locked, .cb.ro { cursor: default; }
    input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: inherit; }
    .box {
      flex: none; width: 18px; height: 18px; border-radius: 4px; box-sizing: border-box;
      border: 1.5px solid var(--mp-text-dim, #a3a3a3); background: var(--mp-surface);
      display: inline-grid; place-items: center; transition: background .12s, border-color .12s;
    }
    .box::after {
      content: ''; width: 5px; height: 9px; margin-top: -2px; border: solid transparent;
      border-width: 0 2px 2px 0; transform: rotate(45deg);
    }
    input:checked + .box { background: var(--mp-accent, #f0a10e); border-color: var(--mp-accent, #f0a10e); }
    input:checked + .box::after { border-color: var(--mp-accent-ink, #111); }
    input:focus-visible + .box { box-shadow: 0 0 0 2px rgb(240 161 14 / 0.35); }
    .cb.locked input:checked + .box { background: transparent; border-style: dashed; }
    .cb.locked input:checked + .box::after { border-color: var(--mp-accent, #f0a10e); }
    .cb.dis .box { opacity: .35; border-style: dotted; }
    .cb.dis .lbl { opacity: .5; }
    .cb:not(.dis):not(.locked):not(.ro):hover .box { border-color: var(--mp-accent, #f0a10e); }
  `],
})
export class MpCheckbox {
  readonly checked = model(false);
  readonly disabled = input(false);
  /** Ticked state shown but not changeable (e.g. inherited). */
  readonly locked = input(false);
  /** Shown as it is (ticked or not) but not changeable: a read-only view. */
  readonly readonly = input(false);
  readonly label = input('');
  /** Accessible name when there is no visible label. */
  readonly ariaLabel = input('');
  readonly title = input('');

  onChange(e: Event) { this.checked.set((e.target as HTMLInputElement).checked); }
}
