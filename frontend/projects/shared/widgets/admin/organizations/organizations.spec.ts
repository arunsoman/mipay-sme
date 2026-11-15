import { renderWidget } from '../../core/testing';
import def from './organizations.widget';
import fixtures from './organizations.fixtures';

const READ = ['admin.org_directory:read'];

describe('admin.organizations', () => {
  it('asks the server for the first page by name and lists every organization', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['admin.organizations.search', { sort: 'name,asc', page: '0', size: '10' }]]);
    expect(w.state()).toBe('ready');
    expect(w.text('mp-data-table')).toContain('Kalahari Supplies');
  });

  it('shows the warning badges, so a missing feature is seen before anyone runs into it', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const row = w.queryAll('mp-data-table tbody tr').find((r) => r.textContent!.includes('Kalahari Supplies'))!;
    expect(row.querySelector('[data-warning]')!.textContent).toContain("Can't add users");
  });

  it('"Needs attention" asks the server for organizations with any warning', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('Needs attention'))!.click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ warnings: "Access not set,Can't add users,Can't complete KYC,Access not applied", page: '0' });
    const names = w.queryAll('mp-data-table tbody tr').map((r) => r.textContent!);
    expect(names.length).toBe(2);
    expect(names.join()).toContain('Rundu Fresh Produce');
    // switching chips replaces the filter, it never stacks them
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('Pending'))!.click();
    await w.settle();
    const last = w.callList().at(-1)![1];
    expect(last).toMatchObject({ status: 'PENDING' });
    expect(last).not.toHaveProperty('warnings');
  });

  it('searches and sorts on the server', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const box = w.query<HTMLInputElement>('mp-table-search input')!;
    box.value = 'namib';
    box.dispatchEvent(new Event('input'));
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ q: 'namib' });
    expect(w.queryAll('mp-data-table tbody tr').length).toBe(1);
    box.value = '';
    box.dispatchEvent(new Event('input'));
    await w.settle();
    w.queryAll<HTMLButtonElement>('mp-data-table th button.sort').find((b) => b.textContent!.includes('People'))!.click();
    await w.settle();
    w.queryAll<HTMLButtonElement>('mp-data-table th button.sort').find((b) => b.textContent!.includes('People'))!.click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ sort: 'users,desc' });
    expect(w.queryAll('mp-data-table tbody tr')[0].textContent).toContain('Etosha Traders');
  });

  it('picking a row publishes the org to the page', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    w.queryAll<HTMLElement>('mp-data-table tbody tr').find((r) => r.textContent!.includes('Etosha Traders'))!.click();
    await w.settle();
    expect(w.selection.all()['orgId']).toBe('org-sup-2');
  });

  it('shows who and what needs doing on a phone; the rest from tablet up', async () => {
    const phone = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' });
    expect(phone.queryAll('mp-data-table th').map((h) => h.textContent!.replace(/[▲▼↕]/g, '').trim())).toEqual(['Organization', 'Warnings']);
    const laptop = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'md' });
    expect(laptop.queryAll('mp-data-table th').length).toBe(6);
  });

  it('matches its contract with the fixtures (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('self-diagnoses an answer that breaks the contract', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'admin.organizations.search': { items: [{ id: 'o', name: 'X', users: '3', warnings: 'none' }], total: 1, page: 0, size: 10 } } });
    const panel = w.text('[data-diagnostics]');
    expect(panel).toContain('$.items[0].users expected integer, got "3" (string)');
    expect(panel).toContain('$.items[0].kycStatus expected string | null, got nothing (field missing)');
    // and the table still draws (a malformed row never breaks the widget)
    expect(w.text('mp-data-table tbody')).toContain('X');
  });

  it('shows the empty state when there are no organizations', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'empty', cells: READ });
    expect(w.state()).toBe('empty');
    expect(w.text('empty-state')).toContain('No organizations yet');
  });

  it('hides itself on 403 and does not exist without admin.org_directory:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'forbidden', cells: READ });
    expect(forbidden.hidden()).toBe(true);
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] });
    expect(none.state()).toBe('denied');
    expect(none.calls().length).toBe(0);
  });
});
