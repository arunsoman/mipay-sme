import { renderWidget } from '../../core/testing';
import def from './people.widget';
import fixtures from './people.fixtures';

const READ = ['org.user:read'];
const ORG = { orgId: 'org-sup-1' };

describe('org.people', () => {
  it('waits for an organization to be picked, and sends nothing until then', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });   // no selection, no own org
    expect(w.text('empty-state')).toContain('Pick an organization');
    expect(w.calls().length).toBe(0);
  });

  it('asks the server for the picked organization’s people and shows their roles', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    expect(w.callList()).toEqual([['org.people.search', { orgId: 'org-sup-1', sort: 'name,asc', page: '0', size: '10' }]]);
    expect(w.state()).toBe('ready');
    const anna = w.queryAll('mp-data-table tbody tr').find((r) => r.textContent!.includes('Anna Kambatu'))!;
    expect(anna.querySelector('[data-role]')!.textContent).toContain('Organization access');
    const lukas = w.queryAll('mp-data-table tbody tr').find((r) => r.textContent!.includes('Lukas Ndeitunga'))!;
    expect(lukas.querySelector('[data-role-pending]')!.textContent).toContain('Stock clerk (pending)');
    const ngari = w.queryAll('mp-data-table tbody tr').find((r) => r.textContent!.includes('Tjimune'))!;
    expect(ngari.textContent).toContain('no role yet');
  });

  it('searches people and roles on the server', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    const box = w.query<HTMLInputElement>('mp-table-search input')!;
    box.value = 'cashier';
    box.dispatchEvent(new Event('input'));
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ orgId: 'org-sup-1', q: 'cashier' });
    expect(w.queryAll('mp-data-table tbody tr').length).toBe(1);
    expect(w.text('mp-data-table tbody')).toContain('Petrus Shiimi');
  });

  it('the Admins chip is a server filter', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('Admins'))!.click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ orgRole: 'ADMIN', page: '0' });
    expect(w.queryAll('mp-data-table tbody tr').length).toBe(1);
  });

  it('a new pick asks again for the new organization', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    await w.select('orgId', 'org-sup-2');
    expect(w.callList().at(-1)![1]).toMatchObject({ orgId: 'org-sup-2', page: '0' });
  });

  it('shows who and their roles on a phone', async () => {
    const phone = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG, tier: 'xs' });
    expect(phone.queryAll('mp-data-table th').map((h) => h.textContent!.replace(/[▲▼↕]/g, '').trim())).toEqual(['Name', 'Roles']);
  });

  it('matches its contract with the fixtures (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    expect(w.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('says so when the organization has nobody', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG, scenario: 'empty' });
    expect(w.text('mp-data-table')).toContain('Nobody here.');
  });

  it('hides itself on 403 and does not exist without org.user:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'forbidden', cells: READ, selection: ORG });
    expect(forbidden.hidden()).toBe(true);
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [], selection: ORG });
    expect(none.state()).toBe('denied');
  });
});
