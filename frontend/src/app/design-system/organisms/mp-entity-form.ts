import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { readProblem } from '../../core/http';
import { MpCard } from '../atoms/mp-card';
import { FormField } from '../molecules/form-field';
import { MpButton } from '../atoms/mp-button';

/**
 * THE declarative form organism. Every create/act screen (order, refund,
 * tariff, limit, template, incident, complaint, suspend, api-client...) is a
 * SCHEMA - never hand-written fields. Wire validation errors from
 * ProblemDetail["fields"] map back onto fields automatically.
 *
 * schema: [{ key, label, kind: text|number|select, required?, hint?, options?, disabled? }]
 * submit emits the plain payload object; parent awaits its service call and
 * calls .error/.success accordingly.
 */
export interface MpFieldSchema {
  key: string;
  label: string;
  kind: 'text' | 'number' | 'select';
  required?: boolean;
  hint?: string;
  placeholder?: string;
  options?: { value: string; label?: string }[];
}

@Component({
  selector: 'mp-entity-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, FormField, MpButton],
  template: `
    <mp-card>
      <form (submit)="doSubmit($event)" class="grid">
        @for (f of schema(); track f.key) {
          <div class="cell" [style.gridColumn]="fullWidth(f) ? 'span 2' : 'span 1'">
            <mp-form-field
              [key]="f.key" [label]="f.label" [kind]="f.kind" [required]="!!f.required"
              [hint]="f.hint ?? ''" [placeholder]="f.placeholder ?? ''"
              [options]="f.options ?? []" [error]="errors()[f.key] || ''"
              [value]="model[f.key] ?? ''"
              (changed)="set(f.key, $event)" />
          </div>
        }
        <div class="cell foot" [style.gridColumn]="'span 2'">
          @if (message()) {<div class="msg" [class.fail]="msgFailed()">{{ message() }}</div>}
          <span class="grow"></span>
          <mp-button type="submit" variant="primary" [loading]="busy()" [disabled]="busy()">
            {{ submitLabel() }}
          </mp-button>
        </div>
      </form>
    </mp-card>
  `,
  styles: [`
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
    .cell { min-width: 0; }
    .foot { display: flex; align-items: center; gap: var(--sp-3); margin-top: var(--sp-2); }
    .grow { flex: 1; }
    .msg { font-size: var(--fs-sm); color: var(--st-confirmed, var(--mp-teal)); }
    .msg.fail { color: #dc2626; }
  `],
})
export class MpEntityForm {
  readonly schema = input.required<MpFieldSchema[]>();
  readonly initial = input<Record<string, string>>({});
  readonly submitLabel = input('Save');
  readonly submitted = output<Record<string, string>>();
  readonly failure = output<{ title: string; detail: string; code?: string; fields?: Record<string, string> }>();

  readonly busy = signal(false);
  readonly message = signal('');
  readonly msgFailed = signal(false);
  readonly errors = signal<Record<string, string>>({});

  model: Record<string, string> = {};

  clear() {
    this.model = { ...this.initial() };
    this.errors.set({});
    this.message.set(''); 
    this.msgFailed.set(false);
  }

  set(key: string, v: string) { this.model[key] = v; }

  fullWidth(f: MpFieldSchema): boolean {
    return f.kind === 'select' || (f.label ?? '').length > 22;
  }

  /** Parent flow: submit -> parent's async call -> form.done()/form.failWire() */
  doSubmit(e: Event) {
    e.preventDefault();
    const missing = this.schema().filter((f) => f.required && !(this.model[f.key] ?? '').trim());
    if (missing.length) {
      this.errors.set(Object.fromEntries(missing.map((f) => [f.key, 'Required'])));
      return;
    }
    this.busy.set(true);
    this.errors.set({});
    this.message.set('');
    this.msgFailed.set(false);
    this.submitted.emit({ ...this.model });
  }

  /** wire success: show server message, reset busy */
  done(msg?: string) {
    this.busy.set(false);
    this.msgFailed.set(false);
    this.message.set(msg ?? 'Saved');
  }

  /** wire failure: parse ProblemDetail -> field errors (server-side validation, p.8) */
  failWire(err: unknown) {
    this.busy.set(false);
    const p = readProblem(err);
    this.msgFailed.set(true);
    if (p.fields) {
      this.errors.set(p.fields);
      this.message.set(p.title);
    } else {
      this.message.set([p.title, p.detail].filter(Boolean).join(' - ') || 'Failed');
    }
    this.failure.emit(p);
  }

  fail() {
    this.busy.set(false);
    this.msgFailed.set(true);
  }
}
