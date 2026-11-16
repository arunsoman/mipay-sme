import { renderWidget } from '../../core/testing';
import { fill, headings, pickRow, press } from '../../core/spec-helpers';
import def from './network.widget';
import fixtures from './network.fixtures';

const READ = ['org.tree:read'];
const ADMIN = 'org.suspension:administer';

describe('org.network', () => {
  it('reads the tree and lists every org, parents before children', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ });
    expect(w.callList()).toEqual([['org.tree.get', {}]]);
    const names = w.queryAll('mp-data-table tbody tr').map((r) => r.textContent!);
    expect(names[0]).toContain('Swakop Foods Manufacturing');
    expect(names[1]).toContain('Namib Distribution');
    expect(names[2]).toContain('Kalahari Supplies');
  });

  it('picking an org publishes it and shows its details; holds and suspend need the administer cell', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ });
    await pickRow(w, 'Kalahari Supplies');
    expect(w.selection.get('orgId')()).toBe('org-sup-1');
    expect(w.text('[data-hold]')).toContain('Bank account under review');
    expect(w.query('[data-action="lift"]')).toBeNull();
    expect(w.query('[data-action="suspend"]')).toBeNull();
    await w.grant(ADMIN);
    expect(w.query('[data-action="lift"]')).not.toBeNull();
    expect(w.query('[data-action="suspend"]')).not.toBeNull();
  });

  it('suspends with a reason, as the request contract describes, then reloads', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: [...READ, ADMIN] });
    await pickRow(w, 'Oshana Traders');
    fill(w, { reason: 'Unpaid invoices over 90 days' });
    await w.settle();
    await press(w, 'Suspend');
    const cmd = w.lastCommand()!;
    expect(cmd.name).toBe('org.suspend');
    expect(cmd.params).toEqual({ id: 'org-dist-8', body: { reason: 'Unpaid invoices over 90 days' } });
    expect(cmd.request).toEqual([]);
    expect(w.callList().at(-1)![0]).toBe('org.tree.get');
  });

  it('lifts a hold by its id', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: [...READ, ADMIN] });
    await press(w, 'Lift hold', 'mp-data-table');   // right in the list
    expect(w.lastCommand()!.params).toEqual({ sid: 'hold-3', body: {} });
    expect(w.text('[data-flash]')).toContain('The hold on Kalahari Supplies is lifted');
  });

  it('shows a refused suspension', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: [...READ, ADMIN], scenario: 'refused' });
    await pickRow(w, 'Oshana Traders');
    fill(w, { reason: 'Test' });
    await w.settle();
    await press(w, 'Suspend');
    expect(w.text('[data-flash]')).toContain('ANCESTOR_REQUIRED');
  });

  it('never offers to suspend the signed-in org itself', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: [...READ, ADMIN] });
    await pickRow(w, 'Swakop Foods Manufacturing');
    expect(w.query('[data-action="suspend"]')).toBeNull();
  });

  it('matches its contracts, diagnoses a drifted answer, and shows name and status on a phone', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, override: { 'org.tree.get': { id: 'x' } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    const phone = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, tier: 'xs' });
    expect(headings(phone)).toEqual(['Organization', 'Status']);   // plus the unlabelled Lift column
  });

  it('hides on 403 and does not exist without org.tree:read', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
    expect((await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: [] })).state()).toBe('denied');
  });
});
