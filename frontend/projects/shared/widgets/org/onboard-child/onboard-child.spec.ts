import { renderWidget } from '../../core/testing';
import { fill, submitForm } from '../../core/spec-helpers';
import def from './onboard-child.widget';
import fixtures from './onboard-child.fixtures';

const CELLS = ['org.onboarding:create', 'org.rights:read'];
const NEW_ORG = { type: 'SUPPLIER', name: 'Kunene Fresh Produce', ownerName: 'Ndapewa Shikongo', phone: '+264812345678' };

describe('org.onboard-child', () => {
  it('reads what the org may onboard and offers those types', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    expect(w.callList()).toEqual([['org.grantable.get', {}]]);
    const opts = w.queryAll<HTMLOptionElement>('mp-entity-form select option').map((o) => o.value).filter(Boolean);
    expect(opts).toEqual(['DISTRIBUTOR', 'SUPPLIER']);
  });

  it('submits the new org with the type’s default rights, as the request contract describes', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    fill(w, NEW_ORG);
    await submitForm(w);
    const cmd = w.lastCommand()!;
    expect(cmd.name).toBe('org.children.submit');
    expect(cmd.params['body']).toMatchObject({ type: 'SUPPLIER', name: 'Kunene Fresh Produce', businessName: 'Kunene Fresh Produce',
      ownerName: 'Ndapewa Shikongo', phone: '+264812345678', email: null, rightsDirect: ['SUPPLIER'], rightsCeiling: [] });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('waiting for approval');
    expect(w.selection.get('orgId')()).toBe('org-new-77');
  });

  it('shows the refusal with its hint', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS, scenario: 'refused' });
    fill(w, NEW_ORG);
    await submitForm(w);
    expect(w.text('[data-flash]')).toContain('already registered');
  });

  it('says so when the org may onboard nothing', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', cells: CELLS, scenario: 'empty' });
    expect(w.text('empty-state')).toContain('Nothing to onboard');
    expect(w.state()).toBe('empty');
  });

  it('matches its contracts with the fixtures, and diagnoses a drifted answer', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS,
      override: { 'org.grantable.get': { effDirect: 'all', children: [] } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });

  it('needs both cells, and hides on 403', async () => {
    const none = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: ['org.rights:read'] });
    expect(none.state()).toBe('denied');
    const forbidden = await renderWidget(def, fixtures, { portal: 'MANUFACTURER', cells: CELLS, scenario: 'forbidden' });
    expect(forbidden.hidden()).toBe(true);
  });
});
