import { renderWidget, type RenderedWidget } from '../../core/testing';
import def from './onboard-child.widget';
import fixtures from './onboard-child.fixtures';
import { KYC_V3_DOC, KYC_V3_SCHEMA, KYC_V3_TABS } from '../../../kyc/kyc-v3-fixture';

const CELLS = ['org.onboarding:create', 'org.rights:read', 'kyc.form:read'];

/** The spec's field names on each tab (kyc-filed.md), as the form must show them. */
const TAB_FIELDS = KYC_V3_SCHEMA.sections.map((s) => s.fields.map((f) => f.label));

function tabNames(w: RenderedWidget) {
  return w.queryAll('mp-kyc-progress li .name').map((n) => n.textContent!.replace('✓', '').trim());
}

function labelsShown(w: RenderedWidget) {
  return w.queryAll('mp-kyc-schema-editor .fl .lb').map((l) => l.textContent!.replace(/optional$/, '').trim());
}

/** Types / picks one tab's values from the valid document into the form. */
async function fillTab(w: RenderedWidget, i: number) {
  const sec = KYC_V3_SCHEMA.sections[i];
  const value = (KYC_V3_DOC as Record<string, unknown>)[sec.key];
  const rows = (Array.isArray(value) ? value : [value]) as Record<string, unknown>[];
  rows.forEach((row, n) => {
    for (const f of sec.fields) {
      const v = row[f.key];
      if (v === null || v === undefined || f.dataType === 'BOOLEAN') continue;
      const id = sec.kind === 'LIST' ? `${sec.key}[${n}].${f.key}` : `${sec.key}.${f.key}`;
      const el = w.query<HTMLInputElement | HTMLSelectElement>(`[id="${id}"]`);
      if (!el) throw new Error(`no input ${id}`);
      if (el instanceof HTMLSelectElement) {
        const label = KYC_V3_SCHEMA.lists[f.listCode!].find((o) => o.value === v)!.label;
        el.selectedIndex = [...el.options].findIndex((o) => o.textContent!.trim() === label);
        el.dispatchEvent(new Event('change'));
      } else {
        el.value = String(v);
        el.dispatchEvent(new Event('input'));
      }
    }
  });
  await w.settle();
}

async function fillAll(w: RenderedWidget) {
  for (let i = 0; i < 5; i++) {
    await fillTab(w, i);
    await w.click(i < 4 ? '[data-action=next]' : '[data-action=submit]');
  }
}

describe('org.onboard-child', () => {
  it('reads what the org may onboard and the KYC form of the first type: nothing else, nothing created', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    expect(w.callList()).toEqual([['org.grantable.get', {}], ['kyc.schema.type.get', { orgType: 'DISTRIBUTOR' }]]);
    const opts = w.queryAll<HTMLOptionElement>('[data-field=type] option').map((o) => o.value);
    expect(opts).toEqual(['DISTRIBUTOR', 'SUPPLIER']);
    expect(w.calls().some((c) => c.kind === 'command')).toBe(false);
  });

  it('shows the five KYC tabs with their exact titles', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    expect(tabNames(w)).toEqual(KYC_V3_TABS);
    expect(w.text('mp-kyc-schema-editor h3')).toBe(KYC_V3_TABS[0]);
  });

  it('names every field exactly as the spec does, tab by tab', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    for (let i = 0; i < 5; i++) {
      expect(labelsShown(w)).toEqual(TAB_FIELDS[i].map((l) => (l === 'Address' ? 'Address type' : l)));
      if (i < 4) { await fillTab(w, i); await w.click('[data-action=next]'); }
    }
  });

  it('names an address card by its kind: Registered Office Address', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    for (let i = 0; i < 2; i++) { await fillTab(w, i); await w.click('[data-action=next]'); }
    await fillTab(w, 2);
    expect(w.text('mp-kyc-schema-editor .iname')).toContain('Registered Office Address');
  });

  it('will not leave a tab with empty required fields', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    await w.click('[data-action=next]');
    expect(w.text('[data-flash]')).toContain('Fill in the highlighted fields');
    expect(w.text('mp-kyc-schema-editor h3')).toBe(KYC_V3_TABS[0]);
  });

  it('onboards in ONE call: type, the whole five-section KYC and the default rights; shows the system-made login', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    await fillAll(w);
    const commands = w.calls().filter((c) => c.kind === 'command');
    expect(commands.map((c) => c.name)).toEqual(['org.children.onboard']);
    const body = commands[0].params['body'] as Record<string, any>;
    expect(body['type']).toBe('DISTRIBUTOR');
    expect(Object.keys(body['kyc'])).toEqual(KYC_V3_SCHEMA.sections.map((s) => s.key));
    expect(body['kyc']['businessProfile']['legalBusinessName']).toBe('Kalahari Supplies (Pty) Ltd');
    expect(body['kyc']['ubos'][0]['emailAddress']).toBe('ndapewa@kalahari.example.na');
    expect(body['rightsCeiling']).toEqual(['SUPPLIER', 'MERCHANT']);
    expect(commands[0].request).toEqual([]);
    expect(w.text('[data-admin-login]')).toBe('kalaharisuppliesptyltd');
    expect(w.selection.get('orgId')()).toBe('org-new-77');
  });

  it('shows the server’s KYC problems on their tab, and says nothing was created', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS, scenario: 'invalid' });
    await fillAll(w);
    expect(w.text('[data-flash]')).toContain('nothing was created');
    expect(w.text('mp-kyc-schema-editor h3')).toBe(KYC_V3_TABS[3]);
    expect(w.state()).toBe('ready');
  });

  it('shows a refusal with its hint', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS, scenario: 'refused' });
    await fillAll(w);
    expect(w.text('[data-flash]')).toContain('Phone already registered');
  });

  it('says so when the org may onboard nothing', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: CELLS, scenario: 'empty' });
    expect(w.text('empty-state')).toContain('Nothing to onboard');
    expect(w.state()).toBe('empty');
  });

  it('matches its contracts with the fixtures, and diagnoses a drifted answer', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS,
      override: { 'org.grantable.get': { effDirect: 'all', children: [] } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });

  it('needs all three cells, and hides on 403', async () => {
    const none = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: ['org.rights:read', 'kyc.form:read'] });
    expect(none.state()).toBe('denied');
    const forbidden = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS, scenario: 'forbidden' });
    expect(forbidden.hidden()).toBe(true);
  });
});
