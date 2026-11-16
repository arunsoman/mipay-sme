import { renderWidget } from '../../core/testing';
import { pickRow, press } from '../../core/spec-helpers';
import def from './role-approvals.widget';
import fixtures, { REQUESTS } from './role-approvals.fixtures';

const READ = ['rbac.assignment:read', 'org.user:read'];
const DECIDE = 'rbac.assignment:authorize';
const CTX = { orgId: 'org-sup-1' } as const;

describe('rbac.role-approvals', () => {
  it('reads the requests waiting in the org, with people’s names', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ });
    expect(w.callList()).toEqual([['rbac.approvals.members', { id: 'org-sup-1' }], ['rbac.approvals.list', { id: 'org-sup-1' }]]);
    expect(w.text('mp-data-table tbody')).toContain('Lukas Ndeitunga');
    expect(w.text('mp-data-table tbody')).toContain('Stock clerk');
  });

  it('the decision shows only with rbac.assignment:authorize', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ });
    await pickRow(w, 'Lukas');
    expect(w.query('[data-action="decide"] mp-button')).toBeNull();
    await w.grant(DECIDE);
    expect(w.query('[data-action="decide"] mp-button')).not.toBeNull();
  });

  it('approves with the body the contract describes (approve, reason) and reloads', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: [...READ, DECIDE] });
    await pickRow(w, 'Petrus');
    await press(w, 'Approve', '[data-action="decide"]');
    const cmd = w.lastCommand()!;
    expect(cmd.params).toEqual({ id: 'org-sup-1', assignmentId: 'as-5', body: { approve: true, reason: null } });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('Approved');
    expect(w.callList().at(-1)![0]).toBe('rbac.approvals.list');
  });

  it('shows the four-eyes refusal', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: [...READ, DECIDE], scenario: 'refused' });
    await pickRow(w, 'Lukas');
    await press(w, 'Reject', '[data-action="decide"]');
    expect(w.text('[data-flash]')).toContain('second person');
  });

  it('says so when nothing waits; matches contracts; diagnoses drift', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ, scenario: 'empty' })).text('empty-state'))
      .toContain('Nothing to approve');
    const ok = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ,
      override: { 'rbac.approvals.list': REQUESTS.map((r) => ({ ...r, scope: 7 })) } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });

  it('needs both read cells, and hides on 403', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: ['rbac.assignment:read'] })).state()).toBe('denied');
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
