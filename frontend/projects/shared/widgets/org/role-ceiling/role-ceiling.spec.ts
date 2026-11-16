import { renderWidget } from '../../core/testing';
import { press } from '../../core/spec-helpers';
import def from './role-ceiling.widget';
import fixtures, { CEILING } from './role-ceiling.fixtures';

const READ = ['org.rights:read'];
const ADMIN = 'org.rights:administer';
const PICK = { orgId: 'org-dist-7' };

const box = (w: Awaited<ReturnType<typeof renderWidget>>, aria: string) =>
  w.query<HTMLInputElement>(`mp-checkbox input[aria-label="${aria}"]`)!;

describe('org.role-ceiling', () => {
  it('reads the picked org’s ceiling and shows what it may use and hand down', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK });
    expect(w.callList()).toEqual([['org.ceiling.get', { id: 'org-dist-7' }]]);
    expect(w.text('[data-source]')).toContain('Set for this organization');
    expect(box(w, 'May use stock_clerk').checked).toBe(true);
    expect(box(w, 'May hand down stock_clerk').checked).toBe(false);
  });

  it('is read-only without org.rights:administer', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK });
    expect(box(w, 'May use cashier').disabled).toBe(true);
    expect(w.query('[data-action="save"]')).toBeNull();
  });

  it('saves the ticked roles as the request contract describes; roles outside the parent’s hand-down stay off', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: [...READ, ADMIN], selection: PICK });
    expect(box(w, 'May use kyc_reviewer').disabled).toBe(true);
    box(w, 'May use cashier').click();
    await w.settle();
    box(w, 'May use stock_clerk').click();
    await w.settle();
    await press(w, 'Save the ceiling');
    const cmd = w.lastCommand()!;
    expect(cmd.params).toEqual({ id: 'org-dist-7',
      body: { roles: ['cashier', 'org_admin', 'org_viewer'], delegableRoles: ['org_viewer'], inherit: false } });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('saved');
  });

  it('can go back to following the parent', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: [...READ, ADMIN], selection: PICK });
    await press(w, 'Follow the parent');
    expect(w.lastCommand()!.params['body']).toEqual({ roles: null, delegableRoles: null, inherit: true });
    expect(w.text('[data-source]')).toContain('Follows the ceiling of Swakop Foods Manufacturing');
  });

  it('shows the refusal for an org that may not set it', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', orgId: 'org-dist-7', cells: [...READ, ADMIN], scenario: 'refused' });
    await press(w, 'Save the ceiling');
    expect(w.text('[data-flash]')).toContain('only an organization above it');
  });

  it('matches its contracts, diagnoses drift, hides on 403', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK,
      override: { 'org.ceiling.get': { ...CEILING, explicit: 'yes' } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    expect((await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: READ, selection: PICK, scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
