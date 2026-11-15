import { renderWidget } from '../../core/testing';
import def from './tasks.widget';
import fixtures from './tasks.fixtures';

const READ = ['kyc.task:read'];
const ALL = [...READ, 'kyc.task:authorize'];

/** Open a task by clicking its row (the table's rowClick). */
async function pick(w: Awaited<ReturnType<typeof renderWidget>>, name: string) {
  const row = w.queryAll('mp-data-table tbody tr').find((r) => (r.textContent ?? '').includes(name));
  if (!row) throw new Error(`no row for ${name}`);
  row.click();
  await w.settle();
}

describe('kyc.tasks', () => {
  it('asks the server for the first page of the caller’s inbox, oldest first, and lists it', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['kyc.tasks.search', { sort: 'createdAt,asc', page: '0', size: '10' }]]);
    expect(w.state()).toBe('ready');
    expect(w.text('mp-data-table')).toContain('Kalahari Supplies');
    expect(w.text('mp-data-table')).toContain('Compliance review (step 2)');
  });

  it('shows who and where it stands on a phone; stage and date from tablet up', async () => {
    const phone = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' });
    expect(phone.queryAll('mp-data-table th').map((h) => h.textContent!.replace(/[▲▼↕]/g, '').trim())).toEqual(['Organization', 'Status']);
    const laptop = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'md' });
    expect(laptop.queryAll('mp-data-table th').length).toBe(4);
  });

  it('picking a task publishes its org to the page and opens the decision', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await pick(w, 'Namib Distribution');
    expect(w.selection.all()['orgId']).toBe('org-dist-7');
    expect(w.text('[data-selected-task]')).toContain('Namib Distribution');
  });

  it('shows the decision only with kyc.task:authorize, and the moment it is granted', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    await pick(w, 'Kalahari Supplies');
    expect(w.queryAll('[data-action=decide] mp-button').length).toBe(0);
    await w.grant('kyc.task:authorize');
    expect(w.queryAll('[data-action=decide] mp-button').length).toBe(2);
  });

  it('sends the decision with the task id, outcome and note, then reloads the queue', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await pick(w, 'Kalahari Supplies');
    const note = w.query<HTMLInputElement>('[data-selected-task] input')!;
    note.value = 'Bank letter verified';
    note.dispatchEvent(new Event('input'));
    await w.settle();
    const approve = w.queryAll('[data-action=decide] mp-button button').pop()!;
    approve.click();
    await w.settle();
    const c = w.lastCommand()!;
    expect(c.name).toBe('kyc.tasks.decide');
    expect(c.params).toEqual({ taskId: 'task-101', body: { outcome: 'APPROVE', note: 'Bank letter verified' } });
    expect(w.text('mp-alert')).toContain('Approved: Kalahari Supplies is now Approved');
    expect(w.callList().filter(([n]) => n === 'kyc.tasks.search').length).toBe(2);
  });

  it('searches, filters and sorts on the server, not in the browser', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const box = w.query<HTMLInputElement>('mp-table-search input')!;
    box.value = 'swakop';
    box.dispatchEvent(new Event('input'));
    await w.settle();
    expect(w.callList().at(-1)).toEqual(['kyc.tasks.search', { q: 'swakop', sort: 'createdAt,asc', page: '0', size: '10' }]);
    expect(w.text('mp-data-table tbody')).toContain('Swakop Foods Manufacturing');
    expect(w.text('mp-data-table tbody')).not.toContain('Kalahari Supplies');

    box.value = '';
    box.dispatchEvent(new Event('input'));
    await w.settle();
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('Entry check'))!.click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ applicationStatus: 'ENTRY_CHECK', page: '0' });
    expect(w.text('mp-data-table tbody')).toContain('Namib Distribution');
    expect(w.text('mp-data-table tbody')).not.toContain('Kalahari Supplies');

    w.queryAll<HTMLButtonElement>('mp-data-table th button.sort').find((b) => b.textContent!.includes('Organization'))!.click();
    await w.settle();
    expect(w.callList().at(-1)![1]).toMatchObject({ sort: 'orgName,asc' });
  });

  it('is empty, with the empty state, when nothing waits', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'empty', cells: READ });
    expect(w.state()).toBe('empty');
    expect(w.text('empty-state')).toContain('No open reviews');
  });

  it('matches its contract with the fixtures (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await pick(w, 'Kalahari Supplies');
    w.queryAll('[data-action=decide] mp-button button').pop()!.click();
    await w.settle();
    expect(w.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('self-diagnoses an inbox answer that breaks the contract', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'kyc.tasks.search': { items: [{ id: 'task-9', orgName: 'Etosha Traders', stageSeq: 'two' }], total: 1, page: 0, size: 10 } } });
    const panel = w.text('[data-diagnostics]');
    expect(panel).toContain('kyc.tasks.search');
    expect(panel).toContain('$.items[0].stageSeq expected integer, got "two" (string)');
    expect(panel).toContain('$.items[0].orgId expected string | null, got nothing (field missing)');
  });

  it('hides itself on 403 and is not created without kyc.task:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'forbidden', cells: READ });
    expect(forbidden.hidden()).toBe(true);
  });

  it('is not created without kyc.task:read', async () => {
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] });
    expect(none.state()).toBe('denied');
    expect(none.calls().length).toBe(0);
  });

  it('checks what it sends: the decision body matches the request contract', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await pick(w, 'Kalahari Supplies');
    w.queryAll('[data-action=decide] mp-button button').pop()!.click();
    await w.settle();
    expect(w.lastCommand()!.request).toEqual([]);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });
});
