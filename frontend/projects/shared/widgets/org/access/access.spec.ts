import { renderWidget } from '../../core/testing';
import def from './access.widget';
import fixtures from './access.fixtures';

const READ = ['org.rights:read'];
const ALL = [...READ, 'org.rights:administer'];
const ORG = { orgId: 'org-sup-1' };

const box = (w: Awaited<ReturnType<typeof renderWidget>>, id: string) =>
  w.query<HTMLInputElement>(`[data-feature="${id}"] input[type=checkbox]`)!;
const press = async (w: Awaited<ReturnType<typeof renderWidget>>, label: string) => {
  w.queryAll<HTMLButtonElement>('[data-action=save] mp-button button').find((b) => b.textContent!.includes(label))!.click();
  await w.settle();
};

describe('org.access', () => {
  it('waits for an organization to be picked', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });   // no selection, no own org
    expect(w.text('empty-state')).toContain('Pick an organization');
    expect(w.calls().length).toBe(0);
  });

  it('reads the picked organization and shows its menus as switches, with the warnings', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    expect(w.callList()).toEqual([['org.features.get', { orgId: 'org-sup-1' }]]);
    expect(w.state()).toBe('ready');
    expect(w.queryAll('[data-feature]').length).toBe(7);
    expect(box(w, 'onboarding').checked).toBe(true);
    expect(box(w, 'team').checked).toBe(false);
    expect(w.text('[data-warning]')).toContain("Can't add users");
    // the switches match a preset: it is the selected tab
    expect(w.query('mp-tabs button.on')!.textContent).toContain('KYC only');
  });

  it('switching on Team and access, then saving, sends the features and clears the warning', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL, selection: ORG });
    box(w, 'team').click();
    await w.settle();
    expect(w.query('mp-tabs button.on')!.textContent).toContain('Custom');
    await press(w, 'Save');
    const c = w.lastCommand()!;
    expect(c.name).toBe('org.features.set');
    expect(c.params).toEqual({ orgId: 'org-sup-1', body: { preset: null, features: ['onboarding', 'help', 'team'] } });
    expect(c.request).toEqual([]);
    expect(w.query('[data-warning]')).toBeNull();
    expect(w.text('mp-alert')).toContain('Saved. Kalahari Supplies now has 3 features.');
  });

  it('a preset is one click: it sets the switches and is sent by name', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL, selection: ORG });
    w.queryAll<HTMLButtonElement>('mp-tabs button').find((b) => b.textContent!.includes('Full access'))!.click();
    await w.settle();
    expect(w.queryAll<HTMLInputElement>('[data-feature] input').every((i) => i.checked)).toBe(true);
    await press(w, 'Save');
    expect(w.lastCommand()!.params['body']).toMatchObject({ preset: 'full' });
  });

  it('Undo puts the switches back; unchanged, the button applies the same features again', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL, selection: ORG });
    const undo = () => w.queryAll<HTMLButtonElement>('[data-action=save] mp-button button').find((b) => b.textContent!.includes('Undo'))!;
    expect(undo().disabled).toBe(true);
    expect(w.text('[data-action=save]')).toContain('Apply again');
    box(w, 'money').click();
    await w.settle();
    expect(undo().disabled).toBe(false);
    expect(w.text('[data-action=save]')).toContain('Save');
    await press(w, 'Undo');
    expect(box(w, 'money').checked).toBe(false);
    await press(w, 'Apply again');
    expect(w.lastCommand()!.params['body']).toEqual({ preset: 'kyc_only', features: ['onboarding', 'help'] });
  });

  it('only org.rights:administer may save, and the moment it is granted', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    expect(w.query('[data-action=save]')).toBeNull();
    expect(box(w, 'money').disabled).toBe(true);
    await w.grant('org.rights:administer');
    expect(w.query('[data-action=save]')).not.toBeNull();
    expect(box(w, 'money').disabled).toBe(false);
  });

  it('a refusal from RBAC is a warning, not a lost change', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL, selection: ORG, scenario: 'notApplied' });
    expect(w.text('[data-warning]')).toContain('Access not applied');
    expect(w.text()).toContain('you can only give permissions you hold yourself');
  });

  it('a refused save says why', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL, selection: ORG, scenario: 'refused' });
    box(w, 'money').click();
    await w.settle();
    await press(w, 'Save');
    expect(w.text('mp-alert')).toContain('Only an admin of an org above it');
  });

  it('a new pick reloads', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    await w.select('orgId', 'org-sup-2');
    expect(w.callList().at(-1)).toEqual(['org.features.get', { orgId: 'org-sup-2' }]);
  });

  it('matches its contracts (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL, selection: ORG });
    box(w, 'team').click();
    await w.settle();
    await press(w, 'Save');
    expect(w.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('hides itself on 403 and does not exist without org.rights:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'forbidden', cells: READ, selection: ORG });
    expect(forbidden.hidden()).toBe(true);
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [], selection: ORG });
    expect(none.state()).toBe('denied');
  });
});
