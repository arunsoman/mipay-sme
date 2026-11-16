import { renderWidget } from '../../core/testing';
import { headings } from '../../core/spec-helpers';
import def from './my-access.widget';
import fixtures, { ACCESS } from './my-access.fixtures';

describe('rbac.my-access', () => {
  it('reads the signed-in person’s access and lists roles and permissions', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['org.user:read'] });
    expect(w.callList()).toEqual([['rbac.my-access.get', {}]]);
    expect(w.text('[data-roles]')).toBe('Org admin, Stock clerk');
    expect(w.text('mp-data-table tbody')).toContain('The organization’s KYC application');
  });

  it('shows to any one of the three read cells, and to nobody without', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['rbac.role:read'] })).state()).toBe('ready');
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: [] })).state()).toBe('denied');
  });

  it('says so with no permissions; keeps area and verb on a phone', async () => {
    const e = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['org.user:read'], scenario: 'empty' });
    expect(e.text('mp-data-table')).toContain('You hold no permissions.');
    const phone = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['org.user:read'], tier: 'xs' });
    expect(headings(phone)).toEqual(['Area', 'May']);
  });

  it('matches its contract, diagnoses drift, hides on 403', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['org.user:read'] });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['org.user:read'],
      override: { 'rbac.my-access.get': { ...ACCESS, orgId: 5 } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ['org.user:read'], scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
