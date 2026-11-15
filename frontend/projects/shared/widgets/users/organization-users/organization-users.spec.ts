import { renderWidget } from '../../core/testing';
import def from './organization-users.widget';
import fixtures from './organization-users.fixtures';

const READ = ['org.user:read'];
const ALL = [...READ, 'org.user:create', 'org.user:administer', 'org.user:delete'];
type W = Awaited<ReturnType<typeof renderWidget>>;

const rowOf = (w: W, name: string) =>
  w.queryAll('mp-data-table tbody tr').find((r) => (r.textContent ?? '').includes(name))!;
/** The text of every open modal (the widget has three: add, setup link, deactivate). */
const modals = (w: W) => w.queryAll('mp-modal').map((m) => m.textContent ?? '').join(' ');
const press = async (w: W, el: Element | null | undefined) => {
  (el!.matches('button') ? el as HTMLElement : el!.querySelector<HTMLElement>('button')!).click();
  await w.settle();
};

/** Open "Add person", fill the form and submit it. */
async function addPerson(w: W, v: { name: string; phone: string; email: string; role?: 'STAFF' | 'ADMIN' }) {
  await press(w, w.query('page-header [data-action=add]'));
  const inputs = w.queryAll<HTMLInputElement>('mp-modal mp-entity-form input');
  [v.name, v.phone, v.email].forEach((value, i) => { inputs[i].value = value; inputs[i].dispatchEvent(new Event('input')); });
  if (v.role) {
    const sel = w.query<HTMLSelectElement>('mp-modal mp-entity-form select')!;
    sel.value = v.role;
    sel.dispatchEvent(new Event('change'));
  }
  w.query<HTMLFormElement>('mp-modal mp-entity-form form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  await w.settle();
}

describe('users.organization-users', () => {
  it('reads the signed-in user’s own organization (no params) and lists its people', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: READ });
    expect(w.callList()).toEqual([['users.organization.list', {}]]);
    expect(w.state()).toBe('ready');
    expect(w.text('mp-data-table')).toContain('Anna Kambatu');
    expect(w.text('mp-data-table')).toContain('+264812345678');
  });

  it('filters by status with the tabs', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: READ });
    expect(w.text('mp-tabs')).toContain('Pending (1)');
    const pending = w.queryAll('mp-tabs button').find((b) => (b.textContent ?? '').includes('Pending'))!;
    await press(w, pending);
    expect(w.text('mp-data-table')).toContain('Petrus Shiimi');
    expect(w.text('mp-data-table')).not.toContain('Anna Kambatu');
  });

  it('shows each action only with its cell, and the moment it is granted', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: READ });
    expect(w.queryAll('[data-action]').length).toBe(0);
    await w.grant('org.user:create');
    expect(w.query('page-header [data-action=add]')).not.toBeNull();
    await w.grant('org.user:administer');
    expect(rowOf(w, 'Petrus Shiimi').querySelector('[data-action=setup-link]')).not.toBeNull();
    expect(rowOf(w, 'Anna Kambatu').querySelector('[data-action=setup-link]')).toBeNull(); // active: no link
    await w.grant('org.user:delete');
    expect(rowOf(w, 'Anna Kambatu').querySelector('[data-action=deactivate]')).not.toBeNull();
    expect(rowOf(w, 'Ngarikutuke Tjimune').querySelector('[data-action=deactivate]')).toBeNull(); // already deactivated
  });

  it('adds a person with the form’s values and shows them in the list', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ALL });
    await addPerson(w, { name: 'Tomas Amupadhi', phone: '+264816789012', email: 'tomas.amupadhi@kalaharisupplies.example', role: 'ADMIN' });
    const c = w.lastCommand()!;
    expect(c.name).toBe('users.organization.create');
    expect(c.params).toEqual({ body: { name: 'Tomas Amupadhi', phone: '+264816789012',
      email: 'tomas.amupadhi@kalaharisupplies.example', username: null, orgRole: 'ADMIN', password: null } });
    expect(c.request).toEqual([]);
    expect(w.text('mp-alert')).toContain('Tomas Amupadhi added as Admin');
    expect(w.text('mp-data-table')).toContain('Tomas Amupadhi');
  });

  it('asks for the required fields before sending', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ALL });
    await addPerson(w, { name: '', phone: '', email: '' });
    expect(w.calls().filter((c) => c.kind === 'command').length).toBe(0);
    expect(modals(w)).toContain('Required');
  });

  it('shows the server’s field refusals at the fields', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', scenario: 'invalid', cells: ALL });
    await addPerson(w, { name: 'Tomas Amupadhi', phone: '0811', email: 'tomas' });
    expect(modals(w)).toContain('must be a valid email address');
    expect(modals(w)).toContain('must be a Namibian mobile number');
  });

  it('issues a setup link for a pending person and shows it (gateway mode)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ALL });
    await press(w, rowOf(w, 'Petrus Shiimi').querySelector('[data-action=setup-link]'));
    const c = w.lastCommand()!;
    expect(c.name).toBe('users.organization.setup-link');
    expect(c.params).toEqual({ id: 'usr-8e12a2f0', body: {} });
    expect(c.request).toEqual([]);
    expect(modals(w)).toContain('https://idp.mipay.example/setup/once-7Gv2Kp9Lx4');
    expect(modals(w)).toContain('Sent through the configured SMS and email channels');
  });

  it('shows the link from the legacy answer (Message with the link in label)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', scenario: 'legacySetup', cells: ALL });
    await press(w, rowOf(w, 'Petrus Shiimi').querySelector('[data-action=setup-link]'));
    expect(modals(w)).toContain('https://idp.mipay.example/setup/legacy-9Jm4Ts8Q');
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('shows why a setup link was refused', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', scenario: 'setupConflict', cells: ALL });
    await press(w, rowOf(w, 'Petrus Shiimi').querySelector('[data-action=setup-link]'));
    expect(modals(w)).toContain('NO_CREDENTIAL');
    expect(modals(w)).toContain('Credential enrolment has not completed yet');
  });

  it('deactivates a person after confirming, and marks them deactivated', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ALL });
    await press(w, rowOf(w, 'Lukas Ndeitunga').querySelector('[data-action=deactivate]'));
    expect(w.calls().filter((c) => c.kind === 'command').length).toBe(0); // only asks first
    await press(w, w.query('mp-modal [data-action=deactivate]'));
    const c = w.lastCommand()!;
    expect(c.name).toBe('users.organization.deactivate');
    expect(c.params).toEqual({ id: 'usr-7c39dd41', body: {} });
    expect(c.request).toEqual([]);
    expect(w.text('mp-alert')).toContain('User deactivated; sessions deleted');
    expect(rowOf(w, 'Lukas Ndeitunga').querySelector('[data-action=deactivate]')).toBeNull();
  });

  it('refuses to deactivate the last administrator, with the reason', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', scenario: 'conflict', cells: ALL });
    await press(w, rowOf(w, 'Anna Kambatu').querySelector('[data-action=deactivate]'));
    await press(w, w.query('mp-modal [data-action=deactivate]'));
    expect(modals(w)).toContain('LAST_ADMIN');
    expect(modals(w)).toContain('at least one active administrator');
  });

  it('offers no actions on people who are already deactivated or archived', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', scenario: 'locked', cells: ALL });
    expect(w.queryAll('mp-data-table [data-action]').length).toBe(0);
  });

  it('shows who and where they stand on a phone; contacts from tablet up', async () => {
    const phone = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: READ, tier: 'xs' });
    expect(phone.queryAll('mp-data-table th').map((h) => h.textContent!.trim())).toEqual(['Person', 'Status']);
    expect(phone.queryAll('mp-data-table tbody tr')[0].querySelector('td .person')).not.toBeNull();
    const laptop = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: READ, tier: 'md' });
    expect(laptop.queryAll('mp-data-table th').length).toBe(5);
  });

  it('is empty, with the empty state, when the organization has nobody yet', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', scenario: 'empty', cells: READ });
    expect(w.state()).toBe('empty');
    expect(w.text('empty-state')).toContain('No people yet');
  });

  it('hides itself on 403, and is not created without org.user:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'SUPPLIER', scenario: 'forbidden', cells: READ });
    expect(forbidden.hidden()).toBe(true);
    const none = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: [] });
    expect(none.state()).toBe('denied');
    expect(none.calls().length).toBe(0);
  });

  it('matches its contracts with the fixtures (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: ALL });
    await press(w, rowOf(w, 'Petrus Shiimi').querySelector('[data-action=setup-link]'));
    expect(w.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('self-diagnoses a list answer that breaks the contract', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: READ,
      override: { 'users.organization.list': [{ id: 'usr-1', name: 42 }] } });
    const panel = w.text('[data-diagnostics]');
    expect(panel).toContain('users.organization.list');
    expect(panel).toContain('$[0].name expected string | null, got 42 (number)');
  });
});
