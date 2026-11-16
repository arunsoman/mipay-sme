import { renderWidget } from '../../core/testing';
import { fill, headings, press } from '../../core/spec-helpers';
import def from './platform-assignments.widget';
import fixtures, { ASSIGNMENTS } from './platform-assignments.fixtures';

const READ = ['rbac.assignment:read', 'rbac.user:read', 'rbac.role:read'];
const CREATE = 'rbac.assignment:create';
const ADMIN = 'rbac.assignment:administer';

describe('rbac.platform-assignments', () => {
  it('reads active assignments, users and roles; names people', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([
      ['rbac.platform-assignments.list', { status: 'ACTIVE' }],
      ['rbac.platform-assignments.users', {}],
      ['rbac.platform-assignments.roles', {}],
    ]);
    expect(w.text('mp-data-table tbody')).toContain('Tuli Amukwaya');
  });

  it('gives a role, defaulting the organization to the person’s own', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, CREATE] });
    await press(w, 'Give a role', '.bar');
    fill(w, { subject: 'u-anna', role: 'support_desk' }, '[data-assign-form]');
    await w.settle();
    await press(w, 'Give role');
    const cmd = w.lastCommand()!;
    expect(cmd.params).toEqual({ body: { subject: 'u-anna', role: 'support_desk', tenant: 'org-sup-1', scope: 'own', expiresAt: null, reason: null } });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('is given to Anna Kambatu');
  });

  it('revokes with the reason as a query parameter; refusals show', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, ADMIN] });
    await press(w, 'Revoke', 'mp-data-table');
    fill(w, { revokeReason: 'Left MiPay' });
    await w.settle();
    await press(w, 'Revoke role');
    expect(w.lastCommand()!.params).toEqual({ id: 'pa-1', reason: 'Left MiPay' });
    const r = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, ADMIN], scenario: 'refused' });
    await press(r, 'Revoke', 'mp-data-table');
    await press(r, 'Revoke role');
    expect(r.text('[data-flash]')).toContain('LAST_ADMIN');
  });

  it('status tabs ask again; phone; contracts; drift', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('Waiting'))!.click();
    await w.settle();
    expect(w.callList().at(-1)).toEqual(['rbac.platform-assignments.list', { status: 'PENDING_APPROVAL' }]);
    expect(headings(await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' }))).toEqual(['Person', 'Role']);
    expect(w.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'rbac.platform-assignments.list': ASSIGNMENTS.map((a) => ({ ...a, scope: 1 })) } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });

  it('needs all three read cells, and hides on 403', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ['rbac.assignment:read'] })).state()).toBe('denied');
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
