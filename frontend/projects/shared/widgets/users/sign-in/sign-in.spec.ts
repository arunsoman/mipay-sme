import { renderWidget } from '../../core/testing';
import { headings, press } from '../../core/spec-helpers';
import def from './sign-in.widget';
import fixtures, { PEOPLE } from './sign-in.fixtures';

const READ = ['org.user:read'];
const ADMIN = 'org.user:administer';
const CTX = { orgId: 'org-sup-1' } as const;

const row = (w: Awaited<ReturnType<typeof renderWidget>>, text: string) =>
  w.queryAll<HTMLElement>('mp-data-table tbody tr').find((r) => r.textContent!.includes(text))!;

describe('users.sign-in', () => {
  it('reads the org’s people with their credential state', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ });
    expect(w.callList()).toEqual([['users.sign-in.list', { id: 'org-sup-1' }]]);
    expect(row(w, 'Ngarikutuke').textContent).toContain('No credential yet');
  });

  it('offers the right fix per person, only with org.user:administer', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ });
    expect(w.query('[data-action]')).toBeNull();
    await w.grant(ADMIN);
    expect(row(w, 'Petrus').querySelector('[data-action="resend-link"]')).not.toBeNull();
    expect(row(w, 'Anna').querySelector('[data-action="reset"]')).not.toBeNull();
    expect(row(w, 'Anna').querySelector('[data-action="sign-out"]')).not.toBeNull();
  });

  it('confirms, then sends the command for that person with no body', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: [...READ, ADMIN] });
    (row(w, 'Anna').querySelector('[data-action="sign-out"] button') as HTMLButtonElement).click();
    await w.settle();
    await press(w, 'Confirm', 'mp-modal');
    const cmd = w.lastCommand()!;
    expect(cmd.name).toBe('users.sign-in.revoke-sessions');
    expect(cmd.params).toEqual({ id: 'org-sup-1', uid: 'u-anna', body: {} });
    expect(cmd.request).toEqual([]);
    expect(cmd.contract).toEqual([]);
    expect(w.text('[data-flash]')).toContain('Every session is being ended for Anna Kambatu');
  });

  it('shows the refusal with its hint', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: [...READ, ADMIN], scenario: 'refused' });
    (row(w, 'Anna').querySelector('[data-action="reset"] button') as HTMLButtonElement).click();
    await w.settle();
    await press(w, 'Confirm', 'mp-modal');
    expect(w.text('[data-flash]')).toContain('no delivered sign-in credential');
  });

  it('phone, contracts, drift, 403', async () => {
    expect(headings(await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ, tier: 'xs' }))).toEqual(['Person']);
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ })).query('[data-diagnostics]')).toBeNull();
    const drift = await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ,
      override: { 'users.sign-in.list': PEOPLE.map((p) => ({ ...p, idp_enabled: 'yes' })) } });
    expect(drift.query('[data-diagnostics]')).not.toBeNull();
    expect((await renderWidget(def, fixtures, { portal: 'SUPPLIER', ...CTX, cells: READ, scenario: 'forbidden' })).hidden()).toBe(true);
  });
});
