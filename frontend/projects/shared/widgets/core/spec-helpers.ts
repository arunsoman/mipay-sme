/**
 * Small helpers the widget specs share (test code only: imported by *.spec.ts, never by a widget).
 */
import type { RenderedWidget } from './testing';

/** The text input or select of the mp-form-field with this key, inside `scope`. */
export function field(w: RenderedWidget, key: string, scope = ''): HTMLInputElement | HTMLSelectElement {
  const label = w.query(`${scope} label[for="ff-${key}"]`);
  const ff = label?.closest('mp-form-field');
  const el = ff?.querySelector('input, select') as HTMLInputElement | HTMLSelectElement | null;
  if (!el) throw new Error(`no form field "${key}" in "${scope || 'the widget'}"`);
  return el;
}

/** Types / picks values into form fields by key. */
export function fill(w: RenderedWidget, values: Record<string, string>, scope = '') {
  for (const [k, v] of Object.entries(values)) {
    const el = field(w, k, scope);
    el.value = v;
    el.dispatchEvent(new Event(el.tagName === 'SELECT' ? 'change' : 'input'));
  }
}

/** Submits the mp-entity-form inside `scope`. */
export async function submitForm(w: RenderedWidget, scope = '') {
  w.query<HTMLFormElement>(`${scope} mp-entity-form form`)!.dispatchEvent(new Event('submit', { cancelable: true }));
  await w.settle();
}

/** Clicks the mp-button whose text contains `text` (inside `scope`). */
export async function press(w: RenderedWidget, text: string, scope = '') {
  const b = w.queryAll<HTMLElement>(`${scope} mp-button`).find((x) => x.textContent!.includes(text));
  if (!b) throw new Error(`no button "${text}" in "${scope || 'the widget'}"`);
  b.querySelector('button')!.click();
  await w.settle();
}

/** Clicks the data-table row whose text contains `text`. */
export async function pickRow(w: RenderedWidget, text: string, scope = '') {
  const r = w.queryAll<HTMLElement>(`${scope} mp-data-table tbody tr`).find((x) => x.textContent!.includes(text));
  if (!r) throw new Error(`no row "${text}"`);
  r.click();
  await w.settle();
}

/** The column headings of the first data table (sort arrows stripped). */
export function headings(w: RenderedWidget, scope = ''): string[] {
  return w.queryAll(`${scope} mp-data-table th`).map((h) => h.textContent!.replace(/[▲▼↕]/g, '').trim()).filter(Boolean);
}
