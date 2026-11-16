import { renderWidget } from '../../core/testing';
import { fill, headings, press } from '../../core/spec-helpers';
import def from './assignments.widget';
import fixtures from './assignments.fixtures';

const READ = ['rbac.assignment:read', 'org.user:read', 'rbac.role:read'];
const CREATE = 'rbac.assignment:create';
const ADMIN = 'rbac.assignment:administer';
const CTX = { orgId: 'org-sup-1' } as const;

describe('rbac.assignments', () => {
  it('reads assignments, members and roles of the org in context', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ });
    expect(w.callList()).toEqual([
      ['rbac.assignments.list', { id: 'org-sup-1' }],
      ['rbac.assignments.members', { id: 'org-sup-1' }],
      ['rbac.assignments.roles', { id: 'org-sup-1' }],
    ]);
    const body = w.text('mp-data-table tbody');
    expect(body).toContain('Anna Kambatu');
    expect(body).toContain('Stock clerk');
    expect(body).not.toContain('Petrus Shiimi');   // revoked
  });

  it('PLATFORM waits for a picked organization', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.text('empty-state')).toContain('Pick an organization');
    expect(w.calls().length).toBe(0);
  });

  it('gives a role with the body the contract describes', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ });
    expect(w.query('.bar [data-action="assign"]')).toBeNull();
    await w.grant(CREATE);
    await press(w, 'Give a role', '.bar');
    fill(w, { userId: 'u-petrus', role: 'org_admin', scope: 'subtree', reason: 'Covers the Rundu depot' }, '[data-assign-form]');
    await w.settle();
    await press(w, 'Give role');
    const cmd = w.lastCommand()!;
    expect(cmd.params).toEqual({ id: 'org-sup-1',
      body: { userId: 'u-petrus', role: 'org_admin', scope: 'subtree', expiresAt: null, reason: 'Covers the Rundu depot' } });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('waiting for a second person');
  });

  it('takes a role away after confirming; refusals show', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: [...READ, ADMIN] });
    await press(w, 'Take away', 'mp-data-table');
    await press(w, 'Take away', 'mp-modal');
    expect(w.lastCommand()!.params).toEqual({ id: 'org-sup-1', assignmentId: 'as-1', body: { reason: null } });
    const r = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: [...READ, ADMIN], scenario: 'refused' });
    await press(r, 'Take away', 'mp-data-table');
    await press(r, 'Take away', 'mp-modal');
    expect(r.text('[data-flash]')).toContain('LAST_ADMIN');
  });

  it('matches its contracts, diagnoses drift, and keeps person and role on a phone', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ,
      override: { 'rbac.assignments.list': [{ id: 'x', subject: 'u', role: 'r' }] } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    const phone = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ, tier: 'xs' });
    expect(headings(phone)).toEqual(['Person', 'Role']);
  });

  it('needs all three read cells, and hides on 403', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: ['rbac.assignment:read'] })).state()).toBe('denied');
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
