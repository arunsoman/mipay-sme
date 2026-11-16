import { renderWidget } from '../../core/testing';
import { headings, pickRow } from '../../core/spec-helpers';
import def from './resources.widget';
import fixtures, { RESOURCES } from './resources.fixtures';

const READ = ['rbac.role:read'];

describe('rbac.resources', () => {
  it('lists the permission rows; picking one shows the endpoints it guards', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['rbac.resources.list', {}]]);
    await pickRow(w, 'org.user');
    expect(w.text('[data-selected-row]')).toContain('/api/orgs/me/users');
  });

  it('searches endpoint paths too', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const box = w.query<HTMLInputElement>('mp-table-search input')!;
    box.value = 'decision';
    box.dispatchEvent(new Event('input'));
    await w.settle();
    expect(w.queryAll('mp-data-table tbody tr').length).toBe(1);
    expect(w.text('mp-data-table tbody')).toContain('kyc.task');
  });

  it('empty, phone, contract and drift', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'empty' })).state()).toBe('empty');
    expect(headings(await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' }))).toEqual(['Row', 'Verbs']);
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ })).query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'rbac.resources.list': RESOURCES.map((r) => ({ ...r, service: 1 })) } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });

  it('hides on 403 and does not exist without rbac.role:read', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] })).state()).toBe('denied');
  });
});
