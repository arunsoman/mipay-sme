import { renderWidget } from '../../core/testing';
import { press } from '../../core/spec-helpers';
import def from './rights.widget';
import fixtures, { RIGHTS } from './rights.fixtures';

const READ = ['org.rights:read'];
const ADMIN = 'org.rights:administer';
const PICK = { orgId: 'org-dist-7' };

const box = (w: Awaited<ReturnType<typeof renderWidget>>, scope: string, label: string) =>
  w.queryAll<HTMLElement>(`${scope} mp-checkbox`).find((c) => c.textContent!.trim() === label)!.querySelector('input')!;

describe('org.rights', () => {
  it('reads the picked org’s rights and shows them ticked', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK });
    expect(w.callList()).toEqual([['org.rights.get', { id: 'org-dist-7' }]]);
    expect(box(w, '[data-direct]', 'Supplier').checked).toBe(true);
    expect(box(w, '[data-ceiling]', 'Merchant').checked).toBe(true);
    expect(box(w, '[data-direct]', 'Supplier').disabled).toBe(true);
    expect(w.query('[data-action="narrow"]')).toBeNull();
  });

  it('narrows: unticked types are left out of the body', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: [...READ, ADMIN], selection: PICK });
    box(w, '[data-ceiling]', 'Merchant').click();
    await w.settle();
    await press(w, 'Save');
    const cmd = w.lastCommand()!;
    expect(cmd.params).toEqual({ id: 'org-dist-7', body: { rightsDirect: ['DISTRIBUTOR', 'SUPPLIER'], rightsCeiling: ['SUPPLIER'] } });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('saved');
  });

  it('shows a refusal', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', orgId: 'org-dist-7', cells: [...READ, ADMIN], scenario: 'refused' });
    await press(w, 'Save');
    expect(w.text('[data-flash]')).toContain('ANCESTOR_REQUIRED');
  });

  it('waits for an org in PLATFORM; matches contracts; diagnoses drift; hides on 403', async () => {
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(none.text('empty-state')).toContain('Pick an organization');
    const ok = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK,
      override: { 'org.rights.get': { ...RIGHTS, effDirect: 5 } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    expect((await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK, scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
