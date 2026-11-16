import { renderWidget } from '../../core/testing';
import { headings, press } from '../../core/spec-helpers';
import def from './audit.widget';
import fixtures, { ENTRIES } from './audit.fixtures';

const READ = ['ops.audit:read'];

describe('ops.audit', () => {
  it('asks for the first page and shows it', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['ops.audit.page', { page: '0', size: '25' }]]);
    expect(w.text('mp-data-table tbody')).toContain('Role assigned');
    expect(w.text('[data-pager]')).toContain('Page 1 of 2');
  });

  it('pages on the server', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    await press(w, 'Older');
    expect(w.callList().at(-1)).toEqual(['ops.audit.page', { page: '1', size: '25' }]);
    expect(w.text('[data-pager]')).toContain('Page 2 of 2');
  });

  it('empty, phone, contract, drift, 403', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'empty' })).state()).toBe('empty');
    expect(headings(await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' }))).toEqual(['When', 'Did']);
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ })).query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'ops.audit.page': { rows: ENTRIES.slice(0, 2), totalElements: '2', page: 0 } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
