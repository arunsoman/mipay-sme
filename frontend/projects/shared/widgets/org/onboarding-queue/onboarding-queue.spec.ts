import { renderWidget } from '../../core/testing';
import def from './onboarding-queue.widget';
import fixtures from './onboarding-queue.fixtures';

const READ = ['org.onboarding:read'];
const DECIDE = 'org.onboarding:authorize';

const rowWith = (w: Awaited<ReturnType<typeof renderWidget>>, text: string) =>
  w.queryAll('mp-data-table tbody tr').find((r) => r.textContent!.includes(text))!;

describe('org.onboarding-queue', () => {
  it('asks for the waiting requests first and lists them', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ });
    expect(w.callList()).toEqual([['org.onboarding.list', { status: 'PENDING_REVIEW' }]]);
    expect(w.state()).toBe('ready');
    expect(w.text('mp-data-table tbody')).toContain('Namib Distribution');
    expect(w.text('mp-data-table tbody')).not.toContain('Swakop Foods');
  });

  it('the status switch asks the server again; All sends no status', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ });
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('All'))!.click();
    await w.settle();
    expect(w.callList().at(-1)).toEqual(['org.onboarding.list', {}]);
    expect(w.text('mp-data-table tbody')).toContain('Swakop Foods');
  });

  it('picking a request publishes its org and shows the decision only with the authorize cell', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    (rowWith(w, 'Namib Distribution') as HTMLElement).click();
    await w.settle();
    expect(w.selection.get('orgId')()).toBe('org-onb-1');
    expect(w.query('[data-action="decide"] mp-button')).toBeNull();
    await w.grant(DECIDE);
    expect(w.query('[data-action="decide"] mp-button')).not.toBeNull();
  });

  it('approving sends the decision body the contract describes and reloads', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, DECIDE] });
    (rowWith(w, 'Namib Distribution') as HTMLElement).click();
    await w.settle();
    const approve = w.queryAll<HTMLElement>('[data-action="decide"] mp-button').find((b) => b.textContent!.includes('Approve'))!;
    approve.querySelector('button')!.click();
    await w.settle();
    const cmd = w.lastCommand()!;
    expect(cmd.name).toBe('org.onboarding.decide');
    expect(cmd.params).toEqual({ id: 'onb-1', body: { approve: true, note: null, sanctionsResult: null } });
    expect(cmd.request).toEqual([]);
    expect(w.text('mp-alert')).toContain('Approved');
    expect(w.callList().at(-1)![0]).toBe('org.onboarding.list');
  });

  it('shows the refusal when the server refuses the decision', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, DECIDE], scenario: 'refused' });
    (rowWith(w, 'Namib Distribution') as HTMLElement).click();
    await w.settle();
    w.queryAll<HTMLElement>('[data-action="decide"] mp-button').find((b) => b.textContent!.includes('Reject'))!
      .querySelector('button')!.click();
    await w.settle();
    expect(w.text('mp-alert')).toContain('MAKER_CANNOT_ACT');
  });

  it('matches its contracts with the fixtures, and diagnoses a drifted answer', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(ok.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'org.onboarding.list': [{ id: 1 }] } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });

  it('says so when nothing is waiting, and shows who and status on a phone', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'empty' });
    expect(w.text('empty-state')).toContain('No requests here');
    const phone = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' });
    expect(phone.queryAll('mp-data-table th').map((h) => h.textContent!.replace(/[▲▼↕]/g, '').trim())).toEqual(['Organization', 'Status']);
  });

  it('hides itself on 403 and does not exist without org.onboarding:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'forbidden' });
    expect(forbidden.hidden()).toBe(true);
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] });
    expect(none.state()).toBe('denied');
  });
});
