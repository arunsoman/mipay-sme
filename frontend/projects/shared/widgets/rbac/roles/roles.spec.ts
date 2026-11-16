import { renderWidget } from '../../core/testing';
import { headings, pickRow, press } from '../../core/spec-helpers';
import def from './roles.widget';
import fixtures, { PLATFORM_ROLES } from './roles.fixtures';

const READ = ['rbac.role:read'];
const MODIFY = 'rbac.role:modify';
const DELETE = 'rbac.role:delete';
const ORG = { orgId: 'org-sup-1' };

const tick = async (w: Awaited<ReturnType<typeof renderWidget>>, label: string, scope = '[data-edit]') => {
  const cb = w.queryAll<HTMLElement>(`${scope} mp-checkbox`).find((c) => c.textContent!.trim() === label)!;
  cb.querySelector('input')!.click();
  await w.settle();
};

describe('rbac.roles', () => {
  it('PLATFORM lists the standard roles', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['rbac.roles.list', {}]]);
    expect(w.text('mp-data-table tbody')).toContain('Kyc reviewer');
  });

  it('org portals list the standard roles and the org’s own, for the org in context', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: READ });
    expect(w.callList()).toEqual([['rbac.roles.list', { id: 'org-sup-1' }]]);
    expect(w.text('mp-data-table tbody')).toContain('Stock clerk');
  });

  it('shows what a role allows; change and delete need their cells and only show where allowed', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    await pickRow(w, 'Org admin');
    expect(w.text('[data-cells]')).toContain('org.user');
    await w.grant(MODIFY, DELETE);
    expect(w.query('[data-selected-role] [data-action="edit"]')).toBeNull();   // locked
    await pickRow(w, 'Support desk');
    expect(w.query('[data-selected-role] [data-action="edit"]')).not.toBeNull();
    expect(w.query('[data-selected-role] [data-action="delete"]')).not.toBeNull();
    await pickRow(w, 'Kyc reviewer');
    expect(w.query('[data-selected-role] [data-action="delete"]')).toBeNull();  // seeded
  });

  it('PLATFORM change sends only the permissions', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, MODIFY] });
    await pickRow(w, 'Support desk');
    await press(w, 'Change', '[data-selected-role]');
    await tick(w, 'create');   // org.user:create, offered from another role
    await press(w, 'Save role');
    const cmd = w.lastCommand()!;
    expect(cmd.name).toBe('rbac.roles.update');
    expect(cmd.params).toEqual({ name: 'support_desk', body: { permissions: ['org.tree:read', 'org.user:create', 'org.user:read'] } });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('changed');
  });

  it('an org changes its own role: description, base roles and permissions', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: [...READ, MODIFY] });
    await pickRow(w, 'Stock clerk');
    await press(w, 'Change', '[data-selected-role]');
    await tick(w, 'org_admin');
    await press(w, 'Save role');
    const cmd = w.lastCommand()!;
    expect(cmd.params).toMatchObject({ id: 'org-sup-1', name: 'stock_clerk@org-sup-1',
      body: { name: 'stock_clerk@org-sup-1', baseRoles: ['org_viewer', 'org_admin'], permissions: ['org.user:read'] } });
    expect(cmd.request).toEqual([]);
  });

  it('deletes after confirming, and shows a refusal', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: [...READ, DELETE] });
    await pickRow(w, 'Stock clerk');
    await press(w, 'Delete', '[data-selected-role]');
    await press(w, 'Delete role');
    expect(w.lastCommand()!.params).toEqual({ id: 'org-sup-1', name: 'stock_clerk@org-sup-1' });
    const r = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: [...READ, DELETE], scenario: 'refused' });
    await pickRow(r, 'Stock clerk');
    await press(r, 'Delete', '[data-selected-role]');
    await press(r, 'Delete role');
    expect(r.text('[data-flash]')).toContain('ROLE_IN_USE');
  });

  it('matches its contracts, diagnoses drift, and keeps role and count on a phone', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, override: { 'rbac.roles.list': PLATFORM_ROLES.map((r) => ({ ...r, seeded: 'no' })) } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    const phone = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' });
    expect(headings(phone)).toEqual(['Role', 'Permissions']);
  });

  it('hides on 403 and does not exist without rbac.role:read', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] })).state()).toBe('denied');
    void ORG;
  });
});
