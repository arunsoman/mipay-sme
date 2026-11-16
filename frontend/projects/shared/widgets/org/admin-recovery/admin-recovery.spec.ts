import { renderWidget } from '../../core/testing';
import { fill, submitForm } from '../../core/spec-helpers';
import def from './admin-recovery.widget';
import fixtures from './admin-recovery.fixtures';

const CELL = ['org.onboarding:authorize', 'org.tree:read'];
const PICK = { orgId: 'org-dist-7' };
const ADMIN = { adminName: 'Ndapewa Shikongo', phone: '+264812345678', email: 'ndapewa@namibdist.na' };

describe('org.admin-recovery', () => {
  it('waits for a picked organization and reads nothing', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELL });
    expect(w.text('empty-state')).toContain('Pick an organization');
    expect(w.state()).toBe('empty');
    expect(w.calls().length).toBe(0);
  });

  it('asks for a new administrator with the body the contract describes', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELL, selection: PICK });
    expect(w.callList()).toEqual([['org.admin-recovery.org', { id: 'org-dist-7' }]]);
    expect(w.text('[data-org]')).toBe('Namib Distribution');
    fill(w, ADMIN);
    await submitForm(w);
    const cmd = w.lastCommand()!;
    expect(cmd.params).toEqual({ id: 'org-dist-7', body: { ...ADMIN, username: null, note: null } });
    expect(cmd.request).toEqual([]);
    expect(cmd.contract).toEqual([]);
    expect(w.text('[data-flash]')).toContain('Ndapewa Shikongo waits for approval');
  });

  it('shows a refusal and a 422 at the form', async () => {
    const r = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELL, selection: PICK, scenario: 'refused' });
    fill(r, ADMIN);
    await submitForm(r);
    expect(r.text('[data-flash]')).toContain('already waiting');
    const i = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELL, selection: PICK, scenario: 'invalid' });
    fill(i, ADMIN);
    await submitForm(i);
    expect(i.text('mp-entity-form')).toContain('well-formed email');
  });

  it('needs both cells, hides on 403, and diagnoses a drifted org', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: ['org.onboarding:authorize'], selection: PICK })).state()).toBe('denied');
    expect((await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELL, selection: PICK, scenario: 'forbidden' })).hidden()).toBe(true);
    const drift = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELL, selection: PICK,
      override: { 'org.admin-recovery.org': { id: 'org-dist-7', name: 'Namib Distribution' } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });
});
