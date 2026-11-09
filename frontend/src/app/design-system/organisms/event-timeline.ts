import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { EventRow } from '../../core/dto';

/** Organism: vertical event trail (M10 payment detail, TC-ACC-05 evidence). */
@Component({
  selector: 'event-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="tl">
      @for (e of events(); track e.createdAt) {
        <li>
          <div class="dot" [style.background]="color(e.eventType)"></div>
          <div class="grow">
            <div class="row1"><b>{{ pretty(e.eventType) }}</b> <span class="dim">{{ short(e.createdAt) }}</span></div>
            @if (e.meta) {<div class="dim">{{ e.meta }}</div>}
          </div>
        </li>
      }
      @empty { <li class="dim">No events on this payment yet.</li> }
    </ul>
  `,
  styles: [`
    .tl { list-style: none; margin: 0; padding: 0; position: relative; }
    .tl::before { content: ''; position: absolute; left: 7px; top: 6px; bottom: 6px; width: 2px; background: color-mix(in srgb, var(--mp-border) 80%, transparent); }
    li { position: relative; display: flex; gap: var(--sp-4); padding: 6px 0 6px 26px; }
    .dot { width: 14px; height: 14px; border-radius: 50%; margin-top: 3px; flex: none; box-shadow: 0 0 0 3px var(--mp-surface); }
    .row1 { display: flex; gap: var(--sp-2); align-items: baseline; }
  `],
})
export class EventTimeline {
  readonly events = input.required<EventRow[]>();
  readonly colorMap = input<Record<string, string>>({
    ACCEPTED: 'var(--st-open)', CONFIRMED: 'var(--st-confirmed)', FAILED: '#dc2626',
    REFUND: 'var(--mp-amber)', TIMEOUT: 'var(--st-timed_out)', DUPLICATE: 'var(--st-closed)',
  });
  private colorOf(e: string): string | undefined {
    return this.colorMap()[e.toUpperCase()];
  }
  color(e: string): string { return this.colorOf(e) ?? 'var(--mp-border)'; }
  pretty(e: string): string { return e.replace(/_/g, ' ').toLowerCase(); }
  short(t: string): string { return String(t ?? '').slice(5, 16).replace('T', ' '); }
}
