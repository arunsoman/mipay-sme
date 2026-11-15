import { renderWidget } from '../../core/testing';
import def from './orgs.widget';
import fixtures from './orgs.fixtures';

const READ = ['kyc.application:read'];
const type = async (w: Awaited<ReturnType<typeof renderWidget>>, text: string) => {
  const box = w.query<HTMLInputElement>('mp-table-search input')!;
  box.value = text;
  box.dispatchEvent(new Event('input'));
  await w.settle();
};

describe('kyc.orgs', () => {
  it('asks the server for the first page, latest update first, and lists it', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['kyc.orgs.search', { sort: 'updatedAt,desc', page: '0', size: '10' }]]);
    expect(w.state()).toBe('ready');
    expect(w.text('mp-data-table')).toContain('Kalahari Supplies');
  });

  it('searches on the server, and says when nothing matches', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    await type(w, 'etosha');
    expect(w.callList().at(-1)).toEqual(['kyc.orgs.search', { q: 'etosha', sort: 'updatedAt,desc', page: '0', size: '10' }]);
    expect(w.text('mp-data-table tbody')).toContain('Etosha Traders');
    expect(w.text('mp-data-table tbody')).not.toContain('Kalahari Supplies');
    await type(w, 'zzz');
    expect(w.text('mp-data-table tbody')).toContain('Nothing matches your search.');
  });

  it('a status chip is a server filter (a comma means any of)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('Approved'))!.click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ status: 'APPROVED', page: '0' });
    const table = w.text('mp-data-table tbody');
    expect(table).toContain('Etosha Traders');
    expect(table).toContain('Swakop Foods Manufacturing');
    expect(table).not.toContain('Kalahari Supplies');
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('In progress'))!.click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ status: 'DRAFT,ENTRY_CHECK,VERIFYING,IN_REVIEW' });
  });

  it('a header sorts on the server: ascending, then descending', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const header = () => w.queryAll<HTMLButtonElement>('mp-data-table th button.sort').find((b) => b.textContent!.includes('Organization'))!;
    header().click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ sort: 'orgName,asc' });
    expect(w.queryAll('mp-data-table tbody tr')[0].textContent).toContain('Etosha Traders');
    expect(w.query('mp-data-table th[aria-sort=ascending]')).not.toBeNull();
    header().click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ sort: 'orgName,desc' });
    expect(w.queryAll('mp-data-table tbody tr')[0].textContent).toContain('Swakop Foods Manufacturing');
  });

  it('pages on the server with the true total', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'many' });
    expect(w.text('mp-pager')).toContain('1–10 of 37');
    expect(w.queryAll('mp-data-table tbody tr').length).toBe(10);
    w.queryAll<HTMLButtonElement>('mp-pager button').find((b) => b.getAttribute('aria-label') === 'Next page')!.click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ page: '1', size: '10' });
    expect(w.text('mp-pager')).toContain('11–20 of 37');
  });

  it('picking a row publishes the org to the page', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const row = w.queryAll('mp-data-table tbody tr').find((r) => (r.textContent ?? '').includes('Rundu Fresh Produce'))!;
    row.click();
    await w.settle();
    expect(w.selection.all()['orgId']).toBe('org-sup-3');
  });

  it('shows who and their status on a phone; type and date from tablet up', async () => {
    const phone = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' });
    expect(phone.queryAll('mp-data-table th').map((h) => h.textContent!.replace(/[▲▼↕]/g, '').trim())).toEqual(['Organization', 'KYC status']);
    const laptop = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'md' });
    expect(laptop.queryAll('mp-data-table th').length).toBe(4);
  });

  it('matches its contract with the fixtures (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('self-diagnoses an answer that breaks the contract', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'kyc.orgs.search': { items: [{ orgId: 'org-x', orgName: 'Gobabis Agri', depth: '2', isSuspended: 'no' }], total: 1, page: 0, size: 10 } } });
    const panel = w.text('[data-diagnostics]');
    expect(panel).toContain('kyc.orgs.search');
    expect(panel).toContain('$.items[0].depth expected integer, got "2" (string)');
    expect(panel).toContain('$.items[0].isSuspended expected boolean');
  });

  it('shows the empty state when there is nothing yet', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'empty', cells: READ });
    expect(w.state()).toBe('empty');
    expect(w.text('empty-state')).toContain('No KYC applications yet');
  });

  it('hides itself on 403 and does not exist without kyc.application:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'forbidden', cells: READ });
    expect(forbidden.hidden()).toBe(true);
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] });
    expect(none.state()).toBe('denied');
    expect(none.calls().length).toBe(0);
  });
});
