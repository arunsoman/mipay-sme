import {
  ChangeDetectionStrategy, Component, effect, ElementRef, input, OnDestroy, output, signal, viewChild,
} from '@angular/core';

/**
 * The popup every create / edit form in every portal uses, so they all look
 * and behave the same: a blurred backdrop, a TPTS-styled card with an eyebrow,
 * title and ✕, a scrolling body and an optional footer
 * (`<div modal-footer>…</div>`).
 *
 * Esc, ✕ and a backdrop click all ask to close. When `dirty` is true the
 * modal first shows an in-card "Discard changes?" bar instead of closing, so
 * nothing typed is lost by accident. While open the page behind does not
 * scroll and focus moves into the card.
 *
 *   <mp-modal [open]="adding()" title="Add staff" eyebrow="Users" [dirty]="formDirty()"
 *             (closed)="adding.set(false)">
 *     …form…
 *     <div modal-footer><button class="primary">Save</button></div>
 *   </mp-modal>
 */
@Component({
  selector: 'mp-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <div class="backdrop" (click)="requestClose()"></div>
      <div class="modal" [class.wide]="size() === 'wide'" [class.narrow]="size() === 'narrow'"
           role="dialog" aria-modal="true" [attr.aria-label]="title()"
           (keydown.escape)="requestClose()" tabindex="-1" #card>
        <header class="mhead">
          <div class="htext">
            @if (eyebrow()) {<div class="eyebrow">{{ eyebrow() }}</div>}
            <h3>{{ title() }}</h3>
            @if (subtitle()) {<div class="sub">{{ subtitle() }}</div>}
          </div>
          <button class="ghost x" type="button" (click)="requestClose()" aria-label="Close">✕</button>
        </header>
        @if (confirming()) {
          <div class="discard">
            <span>{{ discardText() }}</span>
            <button type="button" (click)="confirming.set(false)">Keep editing</button>
            <button type="button" class="danger" (click)="forceClose()">Discard</button>
          </div>
        }
        <div class="mbody"><ng-content /></div>
        <footer class="mfoot"><ng-content select="[modal-footer]" /></footer>
      </div>
    }
  `,
  styles: [`
    .backdrop { position: fixed; inset: 0; z-index: 90; background: rgba(0, 0, 0, .62);
                backdrop-filter: blur(3px); animation: fade .15s ease; }
    .modal { position: fixed; z-index: 91; left: 50%; top: 5vh; transform: translateX(-50%);
             width: min(720px, calc(100vw - 32px)); max-height: 90vh; display: flex; flex-direction: column;
             background: #111; border: 1px solid var(--a-border); border-radius: 20px;
             box-shadow: 0 30px 80px -20px rgba(0, 0, 0, .8), 0 0 0 1px rgba(240, 161, 14, .06);
             animation: pop .18s ease; outline: none; color: var(--a-text); }
    .modal.wide { width: min(1040px, calc(100vw - 32px)); }
    .modal.narrow { width: min(480px, calc(100vw - 32px)); }
    .mhead { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
             padding: 18px 22px 14px; border-bottom: 1px solid var(--a-border); }
    .htext { min-width: 0; }
    h3 { margin: 4px 0 0; font-size: 22px; color: var(--a-teal); letter-spacing: -0.02em; overflow-wrap: anywhere; }
    .sub { color: var(--a-dim); font-size: var(--fs-sm); margin-top: 4px; }
    .x { padding: 4px 12px; font-size: 14px; flex: none; }
    .discard { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 10px 22px;
               background: color-mix(in srgb, var(--a-red) 10%, transparent);
               border-bottom: 1px solid var(--a-border); font-size: var(--fs-sm); }
    .discard span { flex: 1; min-width: 180px; }
    .mbody { padding: 18px 22px; overflow: auto; display: grid; gap: 12px; min-height: 0; }
    .mfoot { display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; padding: 12px 22px;
             border-top: 1px solid var(--a-border); }
    .mfoot:empty { display: none; }
    @keyframes fade { from { opacity: 0; } }
    @keyframes pop { from { opacity: 0; transform: translate(-50%, 8px) scale(.985); } }
    @media (max-width: 700px) {
      .modal, .modal.wide, .modal.narrow { top: 0; width: 100vw; height: 100vh; max-height: 100vh; border-radius: 0; }
    }
  `],
})
export class Modal implements OnDestroy {
  readonly open = input(false);
  readonly title = input('');
  readonly eyebrow = input<string | null>(null);
  readonly subtitle = input<string | null>(null);
  readonly size = input<'narrow' | 'normal' | 'wide'>('normal');
  /** When true, closing asks first. */
  readonly dirty = input(false);
  readonly discardText = input('Discard your changes? Nothing has been saved.');
  /** Emitted when the modal should close; the host flips `open` off. */
  readonly closed = output<void>();

  readonly confirming = signal(false);
  private readonly card = viewChild<ElementRef<HTMLElement>>('card');

  constructor() {
    effect(() => {
      const isOpen = this.open();
      // only the modal that was open releases the scroll lock (several closed modals may share a page)
      if (isOpen) document.body.style.overflow = 'hidden';
      else if (this.wasOpen) document.body.style.overflow = '';
      this.wasOpen = isOpen;
      if (!isOpen) this.confirming.set(false);
      const el = this.card()?.nativeElement;
      if (el && isOpen) queueMicrotask(() => { if (!el.contains(document.activeElement)) el.focus(); });
    });
  }

  private wasOpen = false;

  ngOnDestroy() { if (this.wasOpen) document.body.style.overflow = ''; }

  requestClose() {
    if (this.dirty() && !this.confirming()) { this.confirming.set(true); return; }
    this.forceClose();
  }

  forceClose() {
    this.confirming.set(false);
    this.closed.emit();
  }
}
