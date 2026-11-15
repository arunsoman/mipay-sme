import { renderWidget } from '../../core/testing';
import def from './orgs.widget';
import fixtures from './orgs.fixtures';

const READ = ['kyc.application:read'];

describe('kyc.orgs', () => {
  it('reads the organizations once, with no params, and lists them', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['kyc.orgs.list', {}]]);
    expect(w.state()).toBe('ready');
    expect(w.text('mp-data-table')).toContain('Kalahari Supplies');
  });

  it('counts the organizations by KYC state', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const kpis = w.text('kpi-row');
    expect(kpis).toContain('Organizations');
    expect(kpis).toMatch(/Organizations\s*6/);
    expect(kpis).toMatch(/In progress\s*3/);
    expect(kpis).toMatch(/Approved\s*2/);
    expect(kpis).toMatch(/Rejected or blocked\s*1/);
  });

  it('filters the table by state with the tabs', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const tab = w.queryAll('mp-tabs button').find((b) => (b.textContent ?? '').includes('Approved'))!;
    tab.click();
    await w.settle();
    const table = w.text('mp-data-table');
    expect(table).toContain('Etosha Traders');
    expect(table).toContain('Swakop Foods Manufacturing');
    expect(table).not.toContain('Kalahari Supplies');
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
    expect(phone.queryAll('mp-data-table th').map((h) => h.textContent!.trim())).toEqual(['Organization', 'KYC status']);
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
      override: { 'kyc.orgs.list': [{ orgId: 'org-x', orgName: 'Gobabis Agri', depth: '2', isSuspended: 'no' }] } });
    const panel = w.text('[data-diagnostics]');
    expect(panel).toContain('kyc.orgs.list');
    expect(panel).toContain('$[0].depth expected integer, got "2" (string)');
    expect(panel).toContain('$[0].isSuspended expected boolean');
  });

  it('shows the empty state when there is nothing yet', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'empty', cells: READ });
    expect(w.state()).toBe('empty');
    expect(w.text('empty-state')).toContain('No organizations yet');
  });

  it('hides itself on 403 and does not exist without kyc.application:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'forbidden', cells: READ });
    expect(forbidden.hidden()).toBe(true);
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] });
    expect(none.state()).toBe('denied');
    expect(none.calls().length).toBe(0);
  });
});
