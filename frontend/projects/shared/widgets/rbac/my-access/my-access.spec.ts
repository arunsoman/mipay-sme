import { renderWidget } from '../../core/testing';
import def from './my-access.widget';
import fixtures, { ACCESS } from './my-access.fixtures';

const box = (row: Element, op: string) => row.querySelector(`td[data-op="${op}"] input`) as HTMLInputElement;

describe('rbac.my-access', () => {
  it('reads the person’s access, then the portal’s widgets for their org', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['rbac.role:read'] });
    expect(w.callList()).toEqual([
      ['rbac.my-access.get', {}],
      ['rbac.my-access.widgets', { id: 'org-sup-1', portal: 'SUPPLIER' }],
    ]);
    expect(w.text('[data-roles]')).toBe('Org admin, Stock clerk');
  });

  it('PLATFORM reads the platform catalogue (no org id)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ['admin.access:read'] });
    expect(w.callList()[1]).toEqual(['rbac.my-access.widgets', { portal: 'PLATFORM' }]);
  });

  it('shows only widgets with a tick, ticked where held, and nothing can be changed', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['rbac.role:read'] });
    const rows = w.queryAll('tr[data-widget]');
    const ids = rows.map((r) => r.getAttribute('data-widget'));
    expect(ids).toContain('kyc.application');
    expect(ids).toContain('users.organization-users');
    expect(ids).not.toContain('kyc.tasks');      // held nothing there
    expect(ids).not.toContain('kyc.orgs');       // registered without operations
    const app = rows.find((r) => r.getAttribute('data-widget') === 'kyc.application')!;
    expect(['view', 'create', 'modify', 'approve'].map((o) => box(app, o).checked)).toEqual([true, false, true, false]);
    for (const r of rows) for (const o of ['view', 'create', 'modify', 'approve']) expect(box(r, o).disabled).toBe(true);
  });

  it('is gated on the catalogue cells, and says so when the roles give nothing', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['org.user:read'] })).state()).toBe('denied');
    const e = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['rbac.role:read'], scenario: 'empty' });
    expect(e.state()).toBe('empty');
    expect(e.text('mp-operations-grid')).toContain('Your roles give you nothing on this portal.');
  });

  it('explains a refused catalogue, matches its contract, diagnoses drift, hides on 403', async () => {
    const r = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['rbac.role:read'], scenario: 'catalogueRefused' });
    expect(r.query('mp-alert')).not.toBeNull();
    const ok = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['rbac.role:read'] });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['rbac.role:read'],
      override: { 'rbac.my-access.get': { ...ACCESS, orgId: 5 } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['rbac.role:read'], scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
