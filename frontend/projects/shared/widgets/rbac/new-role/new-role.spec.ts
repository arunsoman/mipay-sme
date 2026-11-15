import { renderWidget } from '../../core/testing';
import { buildOperationsTree, opState, toggleOperation, widgetCells } from '../../../rbac/role-operations-model';
import def from './new-role.widget';
import fixtures, { WIDGETS } from './new-role.fixtures';
import { roleIdOf } from './new-role.component';

const READ = ['rbac.role:read', 'admin.access:read'];
const ALL = [...READ, 'rbac.role:create'];
type W = Awaited<ReturnType<typeof renderWidget>>;

async function type(w: W, field: string, value: string) {
  const el = w.query<HTMLInputElement>(`[data-field=${field}] input`)!;
  el.value = value;
  el.dispatchEvent(new Event('input'));
  await w.settle();
}
const row = (w: W, widget: string, submenu?: string) =>
  w.queryAll(`tr[data-widget="${widget}"]`).find((r) => !submenu || r.getAttribute('data-submenu') === submenu)!;
const box = (r: Element, op: string) => r.querySelector<HTMLInputElement>(`td[data-op=${op}] input`)!;
async function tick(w: W, widget: string, op: string, submenu?: string) {
  box(row(w, widget, submenu), op).click();
  await w.settle();
}
async function createRole(w: W) {
  w.query<HTMLButtonElement>('[data-action=create] button')!.click();
  await w.settle();
}

describe('role-operations-model', () => {
  const tasks = widgetCells(WIDGETS[0], 'PLATFORM/kyc/reviews', true);

  it('grants requires (+ the submenu menu cell) for View, and the action cells plus View for the rest', () => {
    expect(tasks.cells.view).toEqual(['kyc.task:read', 'nav.platform_kyc_reviews:read']);
    expect(tasks.cells.approve).toEqual([...tasks.cells.view, 'kyc.task:authorize']);
    expect(tasks.supported).toEqual({ view: true, create: false, modify: false, approve: true });
  });

  it('turns a bare row into row:read, and leaves a widget registered without operations untickable', () => {
    const legacy = widgetCells(WIDGETS[3], null, false);
    expect(legacy.supported).toEqual({ view: false, create: false, modify: false, approve: false });
    expect(opState(legacy, 'view', new Set(), new Set())).toBe('unsupported');
  });

  it('places widgets under every submenu whose rows they require, and leaves empty submenus out', () => {
    const tree = buildOperationsTree('PLATFORM', WIDGETS);
    // the admin console's KYC menu: the queue under Review queue, the application widgets under Applications
    const reviews = tree.flatMap((m) => m.submenus).find((s) => s.key === 'PLATFORM/kyc/reviews')!;
    expect(reviews.widgets.map((w) => w.id)).toEqual(['kyc.tasks']);
    const applications = tree.flatMap((m) => m.submenus).find((s) => s.key === 'PLATFORM/kyc/applications')!;
    expect(applications.widgets.map((w) => w.id)).toEqual(['kyc.application', 'kyc.orgs']);
    expect(tree.every((m) => m.submenus.every((s) => s.widgets.length > 0))).toBe(true);
  });

  it('ticking an action brings View; unticking View takes everything; inherited cells stay', () => {
    let sel = toggleOperation(tasks, 'approve', true, new Set());
    expect(opState(tasks, 'view', sel, new Set())).toBe('own');
    sel = toggleOperation(tasks, 'approve', false, sel);
    expect([...sel].sort()).toEqual([...tasks.cells.view].sort());
    sel = toggleOperation(tasks, 'view', false, toggleOperation(tasks, 'approve', true, sel));
    expect(sel.size).toBe(0);
    const inh = new Set(tasks.cells.view);
    expect(opState(tasks, 'view', new Set(), inh)).toBe('inherited');
    expect([...toggleOperation(tasks, 'approve', true, new Set(), inh)]).toEqual(['kyc.task:authorize']);
  });
});

describe('rbac.new-role', () => {
  it('reads the PLATFORM widget catalogue for the portal and the platform roles', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['rbac.new-role.widgets', { portal: 'PLATFORM' }], ['rbac.new-role.roles', {}]]);
    expect(w.state()).toBe('ready');
    expect(w.text('mp-operations-grid')).toContain('KYC review queue');
  });

  it('shows View, Create, Modify and Approve for every widget, in a sticky header', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const heads = w.queryAll('mp-operations-grid thead th.op').map((h) => h.textContent!.trim());
    expect(heads).toEqual(['View', 'Create', 'Modify', 'Approve']);
    expect(getComputedStyle(w.query('mp-operations-grid thead th')!).position).toBe('sticky');
    for (const r of w.queryAll('tr[data-widget]')) expect(r.querySelectorAll('td[data-op] mp-checkbox').length).toBe(4);
  });

  it('lets only the operations a widget supports be ticked', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    const tasks = row(w, 'kyc.tasks');
    expect(['view', 'create', 'modify', 'approve'].map((o) => !box(tasks, o).disabled)).toEqual([true, false, false, true]);
    const app = row(w, 'kyc.application');
    expect(['view', 'create', 'modify', 'approve'].map((o) => !box(app, o).disabled)).toEqual([true, false, true, true]);
    const legacy = row(w, 'kyc.orgs');
    expect(['view', 'create', 'modify', 'approve'].every((o) => box(legacy, o).disabled)).toBe(true);
  });

  it('in an org portal reads the org’s catalogue and roles, and builds on its roles', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ['rbac.role:read'] });
    expect(w.callList()).toEqual([
      ['rbac.new-role.widgets', { id: 'org-sup-1', portal: 'SUPPLIER' }], ['rbac.new-role.roles', { id: 'org-sup-1' }]]);
    expect(w.query('[data-field=portal]')).toBeNull();
    expect(w.text('[data-bases]')).toContain('Org viewer');
  });

  it('shows Create role only with rbac.role:create, and the moment it is granted', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.query('[data-action=create]')).toBeNull();
    await w.grant('rbac.role:create');
    expect(w.query('[data-action=create]')).not.toBeNull();
  });

  it('creates a platform role with the ticked cells and the roles it inherits', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await type(w, 'name', 'KYC Approver');
    await type(w, 'description', 'Approves KYC applications');
    await tick(w, 'kyc.tasks', 'approve', 'PLATFORM/kyc/reviews');
    await createRole(w);
    const c = w.lastCommand()!;
    expect(c.name).toBe('rbac.new-role.create');
    expect(c.params).toEqual({ body: { name: 'kyc_approver', description: 'Approves KYC applications', kind: 'PLATFORM', inherits: [],
      permissions: ['kyc.task:authorize', 'kyc.task:read', 'nav.platform_kyc_reviews:read'] } });
    expect(c.request).toEqual([]);
    expect(w.text('mp-alert')).toContain('Role Kyc approver created with 3 permission(s)');
  });

  it('shows cells from the roles it builds on ticked and locked, and leaves them out of the body', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL, config: { menuCells: false } });
    w.query<HTMLInputElement>('[data-bases] input')!.click(); // kyc_reviewer
    await w.settle();
    const r = row(w, 'kyc.tasks');
    expect(r.querySelector('td[data-op=approve]')!.getAttribute('data-state')).toBe('inherited');
    expect(box(r, 'approve').disabled).toBe(true);
    await type(w, 'name', 'Senior reviewer');
    await createRole(w);
    expect(w.lastCommand()!.params).toEqual({ body: { name: 'senior_reviewer', description: '', kind: 'PLATFORM',
      inherits: ['kyc_reviewer'], permissions: [] } });
  });

  it('makes an org role for another portal from PLATFORM (kind ORG)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL, config: { menuCells: false } });
    const sel = w.query<HTMLSelectElement>('[data-field=portal] select')!;
    sel.value = 'SUPPLIER';
    sel.dispatchEvent(new Event('change'));
    await w.settle();
    expect(w.callList().at(-2)).toEqual(['rbac.new-role.widgets', { portal: 'SUPPLIER' }]);
    await type(w, 'name', 'Supplier clerk');
    await tick(w, 'users.organization-users', 'create');
    await createRole(w);
    expect(w.lastCommand()!.params).toEqual({ body: { name: 'supplier_clerk', description: '', kind: 'ORG', inherits: [],
      permissions: ['org.user:create', 'org.user:read'] } });
  });

  it('creates an org-defined role with its base roles in an org portal', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ['rbac.role:read', 'rbac.role:create'],
      config: { menuCells: false } });
    w.queryAll<HTMLInputElement>('[data-bases] input')[0].click(); // org_admin
    await w.settle();
    await type(w, 'name', 'People desk');
    await tick(w, 'users.organization-users', 'approve');
    await createRole(w);
    const c = w.lastCommand()!;
    expect(c.params).toEqual({ id: 'org-sup-1', body: { name: 'people_desk', description: '', baseRoles: ['org_admin'],
      permissions: ['org.user:administer', 'org.user:delete', 'org.user:read'] } });
    expect(c.request).toEqual([]);
  });

  it('refuses a typed name it cannot turn into a role id, before sending', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await type(w, 'name', 'x');
    expect(w.query<HTMLButtonElement>('[data-action=create] button')!.disabled).toBe(true);
    expect(w.text('[data-field=name]')).toContain('At least 3 letters');
    expect(roleIdOf('  Ödön 2nd Approver ')).toBe('odon_2nd_approver');
    expect(roleIdOf('2fa admins')).toBe('r_2fa_admins');
  });

  it('shows a separation-of-duty refusal', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'sodConflict', cells: ALL });
    await type(w, 'name', 'Apply and approve');
    await createRole(w);
    expect(w.text('mp-alert')).toContain('SOD_CONFLICT');
    expect(w.text('mp-alert')).toContain('separation-of-duty');
  });

  it('leaves the required name to the server and shows its refusal', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'nameRequired', cells: ALL });
    await createRole(w);
    expect((w.lastCommand()!.params['body'] as { name: string }).name).toBe('');
    expect(w.text('mp-alert')).toContain('A role name is required');
  });

  it('is empty when the portal has no widgets, and hides on 403', async () => {
    const empty = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'empty', cells: READ });
    expect(empty.state()).toBe('empty');
    expect(empty.text('mp-operations-grid')).toContain('No widgets on this portal yet.');
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'forbidden', cells: READ });
    expect(forbidden.hidden()).toBe(true);
  });

  it('matches its contracts with the fixtures (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await type(w, 'name', 'Clean role');
    await createRole(w);
    expect(w.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('self-diagnoses a catalogue answer that breaks the contract', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'rbac.new-role.widgets': [{ ...WIDGETS[0], operations: ['view', 'execute'] }] } });
    expect(w.text('[data-diagnostics]')).toContain('rbac.new-role.widgets');
    expect(w.text('[data-diagnostics]')).toContain('$[0].operations[1]');
  });

  it('keeps the four columns on a phone', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' });
    expect(w.queryAll('mp-operations-grid thead th.op').length).toBe(4);
  });
});
