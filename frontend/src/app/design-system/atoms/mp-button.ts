import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/** Atom: the one button. variant + size typed; danger = destructive. */
@Component({
  selector: 'mp-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button [attr.type]="type()" [class]="cls()" [disabled]="disabled() || loading()" (click)="click()">
      @if (loading()) {<span class="spin"></span>}
      <ng-content />
    </button>
  `,
  styles: [`
    :host { display: inline-flex; }
    button {
      display: inline-flex; align-items: center; gap: var(--sp-2);
      border: 1px solid transparent; border-radius: var(--radius-1);
      padding: 8px var(--sp-4); font: inherit; font-weight: 600; font-size: var(--fs-md);
      cursor: pointer; transition: filter .15s ease, opacity .15s ease;
    }
    button:disabled { opacity: .55; cursor: not-allowed; }
    .primary { background: var(--mp-accent); color: var(--mp-accent-ink); }
    .primary:hover:not(:disabled) { filter: brightness(1.08); }
    .ghost { background: transparent; color: var(--mp-text); border-color: var(--mp-border); }
    .danger { background: #dc2626; color: #fff; }
    .spin { width: 12px; height: 12px; border: 2px solid #ffffff55; border-top-color: #fff; border-radius: 50%; animation: r .7s linear infinite; }
    @keyframes r { to { transform: rotate(360deg); } }
  `],
})
export class MpButton {
  readonly variant = input<'primary' | 'ghost' | 'danger'>('primary');
  readonly type = input<'button' | 'submit' | 'reset'>('button');
  readonly onClick = output<void>();
  click() { if (!this.disabled() && !this.loading()) this.onClick.emit(); }
  readonly disabled = input(false, { transform: (v: unknown) => v !== undefined && v !== false && v !== 'false' });
  readonly loading = input(false, { transform: (v: unknown) => v === true || v === 'true' });

  readonly cls = computed(() => this.variant() ?? 'primary');
}
