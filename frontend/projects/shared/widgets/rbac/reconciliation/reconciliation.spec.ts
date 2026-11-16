import { renderWidget } from '../../core/testing';
import def from './reconciliation.widget';
import fixtures, { REPORT } from './reconciliation.fixtures';

const READ = ['rbac.assignment:read', 'rbac.role:read'];
const PICK = { orgId: 'org-sup-1' };

describe('rbac.reconciliation', () => {
  it('shows the mode, then waits for an organization', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['rbac.mode.get', {}]]);
    expect(w.text('[data-mode]')).toContain('shadow');
    expect(w.text('empty-state')).toContain('Pick an organization');
  });

  it('reads the picked org’s report and lists the disagreements', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: PICK });
    expect(w.callList()).toContainEqual(['rbac.recon.get', { orgId: 'org-sup-1' }]);
    expect(w.text('kpi-row')).toContain('Disagreements');
    expect(w.text('mp-data-table tbody')).toContain('Admin in the core, viewer in RBAC');
  });

  it('says so when nothing disagrees', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: PICK, scenario: 'empty' });
    expect(w.text('mp-data-table')).toContain('Nothing disagrees');
  });

  it('matches its contracts, diagnoses drift, needs both cells, hides on 403', async () => {
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: PICK })).query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: PICK,
      override: { 'rbac.recon.get': { ...REPORT, lifecycle: 3 } } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ['rbac.role:read'] })).state()).toBe('denied');
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, selection: PICK, scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
