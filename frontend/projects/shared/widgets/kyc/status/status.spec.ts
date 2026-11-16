import { renderWidget } from '../../core/testing';
import { press } from '../../core/spec-helpers';
import def from './status.widget';
import fixtures, { VIEW } from './status.fixtures';

const VIEW_DRIFT = { ...VIEW, version: 'three' };

const READ = ['kyc.application:read'];
const MODIFY = 'kyc.application:modify';

describe('kyc.status', () => {
  it('reads the org’s own application, shows the next step and publishes the org', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', cells: READ });
    expect(w.callList()).toEqual([['kyc.mine.get', {}]]);
    expect(w.text('[data-next]')).toContain('submit it for review');
    expect(w.selection.get('orgId')()).toBe('org-dist-7');
  });

  it('shows the decision on an approved application', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', cells: READ, scenario: 'approved' });
    expect(w.text()).toContain('All documents in order.');
    expect(w.query('[data-action="upgrade"]')).toBeNull();
  });

  it('offers the upgrade on a draft only with kyc.application:modify, and sends it with no body', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', cells: READ });
    expect(w.query('[data-action="upgrade"]')).toBeNull();
    await w.grant(MODIFY);
    await press(w, 'Move to the current form');
    const cmd = w.lastCommand()!;
    expect(cmd.name).toBe('kyc.mine.upgrade');
    expect(cmd.params).toEqual({ id: 'org-dist-7', body: {} });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('current form');
  });

  it('shows a refused upgrade', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', cells: [...READ, MODIFY], scenario: 'refused' });
    await press(w, 'Move to the current form');
    expect(w.text('[data-flash]')).toContain('KYC_NOT_DRAFT');
  });

  it('matches its contracts with the fixtures, and diagnoses a drifted answer', async () => {
    const ok = await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', cells: READ });
    expect(ok.query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', cells: READ,
      override: { 'kyc.mine.get': { ...VIEW_DRIFT } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
  });

  it('hides on 403 and does not exist without kyc.application:read', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
    expect((await renderWidget(def, fixtures, { portal: 'DISTRIBUTOR', cells: [] })).state()).toBe('denied');
  });
});
