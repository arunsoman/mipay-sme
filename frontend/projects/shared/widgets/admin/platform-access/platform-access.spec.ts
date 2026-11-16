import { renderWidget } from '../../core/testing';
import { headings, pickRow, press } from '../../core/spec-helpers';
import def from './platform-access.widget';
import fixtures, { USERS } from './platform-access.fixtures';

const READ = ['admin.access:read'];
const ADMIN = 'admin.access:administer';

describe('admin.platform-access', () => {
  it('lists users; picking one reads their links and sessions', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['admin.access.list', {}]]);
    await pickRow(w, 'u-anna');
    expect(w.callList().at(-1)).toEqual(['admin.access.detail', { userId: 'u-anna' }]);
    expect(w.text('[data-selected-user]')).toContain('chrome-android-7f3a');
    expect(w.query('[data-action]')).toBeNull();
  });

  it('ends every session after confirming, with no body', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, ADMIN] });
    await pickRow(w, 'u-anna');
    await press(w, 'End every session', '[data-selected-user]');
    await press(w, 'End sessions', 'mp-modal');
    const cmd = w.lastCommand()!;
    expect(cmd.params).toEqual({ userId: 'u-anna', body: {} });
    expect(cmd.request).toEqual([]);
    expect(w.text('[data-flash]')).toContain('incident inc-204');
  });

  it('disables a sign-in link; refusals show', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, ADMIN] });
    await pickRow(w, 'u-anna');
    await press(w, 'Disable', '[data-selected-user]');
    expect(w.lastCommand()!.params).toEqual({ linkId: 'link-1', body: {} });
    expect(w.text('[data-flash]')).toContain('disabled');
    const r = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [...READ, ADMIN], scenario: 'refused' });
    await pickRow(r, 'u-anna');
    await press(r, 'Disable', '[data-selected-user]');
    expect(r.text('[data-flash]')).toContain('Already disabled');
  });

  it('phone, contracts, drift, 403', async () => {
    expect(headings(await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' }))).toEqual(['User', 'Live sessions']);
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ })).query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'admin.access.list': USERS.map((u) => ({ ...u, activeSessions: '2' })) } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
    expect((await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] })).state()).toBe('denied');
  });
});
