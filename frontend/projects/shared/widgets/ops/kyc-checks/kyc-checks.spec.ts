import { renderWidget } from '../../core/testing';
import { fill, submitForm } from '../../core/spec-helpers';
import def from './kyc-checks.widget';
import fixtures from './kyc-checks.fixtures';

const READ = ['ops.kyc_check:read', 'org.tree:read'];
const CREATE = 'ops.kyc_check:create';
const ORG = { orgId: 'org-sup-1' };

describe('ops.kyc-checks', () => {
  it('waits for an organization and sends nothing until then', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.text('empty-state')).toContain('Pick an organization');
    expect(w.calls().length).toBe(0);
  });

  it('reads the org, then its checks by entity type and id', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    expect(w.callList()).toEqual([
      ['ops.kyc.org.get', { id: 'org-sup-1' }],
      ['ops.kyc-checks.list', { entityType: 'supplier', entityId: 'sup-118' }],
    ]);
    expect(w.text('mp-data-table tbody')).toContain('National ID 85010100123 matched');
  });

  it('records a check only with ops.kyc_check:create, with the body the contract describes', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    expect(w.query('[data-action="record"]')).toBeNull();
    await w.grant(CREATE);
    fill(w, { checkType: 'SANCTIONS', result: 'PASS', details: 'UN and OFAC lists clear' });
    await submitForm(w);
    const cmd = w.lastCommand()!;
    expect(cmd.params).toEqual({ entityType: 'supplier', entityId: 'sup-118',
      body: { checkType: 'SANCTIONS', result: 'PASS', details: 'UN and OFAC lists clear' } });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('Recorded');
    expect(w.callList().at(-1)![0]).toBe('ops.kyc-checks.list');
  });

  it('says when the org has no KYC entity, and shows a refused check', async () => {
    const u = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG, scenario: 'unsupported' });
    expect(u.text('empty-state')).toContain('No KYC checks');
    const r = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, CREATE], selection: ORG, scenario: 'refused' });
    fill(r, { checkType: 'IDENTITY', result: 'PASS' });
    await submitForm(r);
    expect(r.text('[data-flash]')).toContain('Refused');
  });

  it('matches its contracts, and diagnoses a drifted answer', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG,
      override: { 'ops.kyc-checks.list': [{ id: 'x', checkedAt: 'yesterday' }] } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });

  it('hides on 403 and needs both read cells', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: ORG, scenario: 'forbidden' })).hidden()).toBe(true);
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ['ops.kyc_check:read'], selection: ORG })).state()).toBe('denied');
  });
});
