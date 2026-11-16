import { renderWidget } from '../../core/testing';
import { fail } from '../../core/fixtures';
import def from './kyc-application.widget';
import fixtures, { DRAFT } from './kyc-application.fixtures';
import { KYC_V3_TABS } from '../../../kyc/kyc-v3-fixture';

const READ = ['kyc.application:read', 'kyc.form:read'];
const ALL = [...READ, 'kyc.application:modify', 'kyc.application:submit', 'kyc.application:delete'];

describe('kyc.application', () => {
  it('loads the application, its schema and the lists not sent inline, for the own org in an org portal', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ALL });
    const names = w.callList().map(([n, p]) => `${n} ${JSON.stringify(p)}`);
    expect(names).toContain('kyc.application.get {"orgId":"org-sup-1"}');
    expect(names).toContain('kyc.schema.get {"orgId":"org-sup-1"}');
    expect(names).toContain('kyc.list.get {"code":"NA_BANK"}');
    expect(w.state()).toBe('ready');
    expect(w.text()).toContain('Kalahari Supplies');
  });

  it('shows the five KYC tabs with their exact titles (kyc-filed.md)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ALL });
    const tabs = w.queryAll('mp-kyc-progress li .name').map((n) => n.textContent!.replace('✓', '').trim());
    expect(tabs).toEqual(KYC_V3_TABS);
  });

  it('prefers the page selection over the own org (admin directory pick)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', orgId: 'org-root',
      selection: { orgId: 'org-dist-7' }, cells: ALL });
    expect(w.callList()[0]).toEqual(['kyc.application.get', { orgId: 'org-dist-7' }]);
  });

  it('is empty, not an error, when no organization is known yet', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', orgId: null, cells: ALL });
    expect(w.state()).toBe('empty');
    expect(w.query('[data-empty]')).not.toBeNull();
    expect(w.calls().length).toBe(0);
    await w.select('orgId', 'org-sup-1');
    expect(w.state()).toBe('ready');
  });

  it('saves with the expected version and the document as the body', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ALL });
    await w.click('[data-action=save]');
    const c = w.lastCommand()!;
    expect(c.name).toBe('kyc.application.save');
    expect(c.params['orgId']).toBe('org-sup-1');
    expect(c.params['expectedVersion']).toBe(3);
    expect((c.params['body'] as Record<string, any>)['businessProfile']['legalBusinessName']).toBe('Kalahari Supplies (Pty) Ltd');
    expect(w.text()).toContain('Draft saved.');
  });

  it('shows the server field problems on KYC_INVALID', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', scenario: 'invalid', cells: ALL });
    await w.click('[data-action=save]');
    expect(w.text()).toContain('Not saved: 2 problem(s)');
    expect(w.state()).toBe('ready'); // a refused save is not a broken widget
  });

  it('explains a version conflict with the shared KYC hint', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', scenario: 'conflict', cells: ALL });
    await w.click('[data-action=save]');
    expect(w.text()).toContain('someone else saved this application meanwhile');
  });

  it('hides each action without its cell, and shows it the moment it is granted', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: READ });
    expect(w.query('[data-action=save]')).toBeNull();
    expect(w.query('[data-action=submit]')).toBeNull();
    expect(w.query('[data-action=withdraw]')).toBeNull();
    await w.grant('kyc.application:submit');
    expect(w.query('[data-action=submit]')).not.toBeNull();
    expect(w.query('[data-action=save]')).toBeNull();
  });

  it('is read-only when the server says canEdit=false', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', scenario: 'inReview', cells: ALL });
    expect(w.query('[data-action=save]')).toBeNull();
    expect(w.query<HTMLFieldSetElement>('fieldset.sec')?.disabled).toBe(true);
    expect(w.text()).toContain('awaiting review');
  });

  it('is not created without both read rows (requires all)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ['kyc.application:read'] });
    expect(w.state()).toBe('denied');
    expect(w.hidden()).toBe(true);
    expect(w.calls().length).toBe(0);
  });

  it('hides itself when the server forbids the schema (403)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', scenario: 'forbidden', cells: ALL });
    expect(w.state()).toBe('forbidden');
    expect(w.hidden()).toBe(true);
  });

  it('reports an error state when the application cannot be loaded', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', scenario: 'error', cells: ALL });
    expect(w.state()).toBe('error');
    expect(w.text()).toContain('KYC service unavailable');
  });

  it('does not exist in portals outside its contexts', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'MERCHANT', orgId: 'org-m-1', cells: ALL });
    expect(w.state()).toBe('unsupported');
    expect(w.calls().length).toBe(0);
  });

  it('cannot call a source its manifest does not declare', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ALL,
      override: { 'kyc.application.get': fail(500) } });
    expect(w.calls().every((c) => [...def.sources, ...def.commands].includes(c.name))).toBe(true);
  });

  it('matches its contract with the fixtures (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ALL });
    expect(w.calls().every((c) => c.contract === null || c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('self-diagnoses a server answer that breaks the contract, in the widget itself', async () => {
    const drifted = { ...DRAFT, version: '3', canEdit: 'yes', extraFromServer: 1 } as unknown as object;
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ALL,
      override: { 'kyc.application.get': drifted } });
    const get = w.calls().find((c) => c.name === 'kyc.application.get')!;
    expect(get.contract?.map((i) => `${i.level} ${i.path}`)).toEqual([
      'error $.version', 'error $.canEdit', 'warning $.extraFromServer',
    ]);
    const panel = w.text('[data-diagnostics]');
    expect(panel).toContain('kyc.application.get');
    expect(panel).toContain('$.version expected integer, got "3" (string)');
    expect(panel).toContain('$.canEdit expected boolean');
  });

  it('names server failures in the self-check too', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', scenario: 'error', cells: ALL });
    expect(w.text('[data-diagnostics]')).toContain('HTTP 500');
  });

  it('checks what it sends: save and submit bodies match their request contracts', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-sup-1', cells: ALL });
    await w.click('[data-action=submit]');
    const sent = w.calls().filter((c) => c.kind === 'command');
    expect(sent.map((c) => c.name)).toEqual(['kyc.application.save', 'kyc.application.submit']);
    expect(sent.every((c) => c.request?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });
});
