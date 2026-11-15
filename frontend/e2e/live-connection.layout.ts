import { expect, test } from '@playwright/test';

/**
 * The harness against a live API, with the gateway simulated by route mocks (the real local
 * gateway is not needed for this test). It proves the harness side of "FAPI is masked": the
 * widget only sees same-origin /api; the session's CSRF value goes on writes; no Authorization
 * header ever leaves the browser; the signed-in user's real permissions and org drive the
 * widget; and a server answer that breaks the contract is shown in the widget.
 */
test('kyc.tasks against a live API through the gateway session', async ({ page }) => {
  const writes: { headers: Record<string, string>; body: unknown }[] = [];
  const seen: { url: string; auth: boolean }[] = [];
  await page.route('**/wb-service.json', (r) => r.fulfill({ json: {
    id: 'local-admin', label: 'Local stack - admin portal', portal: 'PLATFORM', portalHost: 'localhost', gateway: 'https://127.0.0.1:8443',
  } }));
  await page.route('**/auth/session', (r) => r.fulfill({ json: { uid: 'u-rev', sub: 'reviewer@mipay', csrf: 'csrf-live-123', expires_at: 0 } }));
  await page.route('**/api/**', async (r) => {
    const req = r.request();
    const url = new URL(req.url());
    seen.push({ url: url.pathname, auth: 'authorization' in req.headers() });
    if (url.pathname === '/api/orgs/me/access') return r.fulfill({ json: { orgId: 'org-root', orgName: 'MiPay', orgType: 'PLATFORM', roles: [],
      permissions: ['kyc.task:read', 'kyc.task:authorize'].map((id) => ({ id, row: id.split(':')[0], verb: id.split(':')[1] })) } });
    if (url.pathname === '/api/me') return r.fulfill({ json: { orgId: 'org-root', orgType: 'PLATFORM', name: 'Reviewer' } });
    if (url.pathname === '/api/kyc/tasks/search') return r.fulfill({ json: { total: 2, page: 0, size: 10, items: [
      { id: 'task-1', orgId: 'org-a', orgName: 'Etosha Traders', kind: 'KYC_REVIEW', stageKey: 'COMPLIANCE_REVIEW', stageSeq: 2,
        actorRule: 'PLATFORM_FUNCTION:kyc_reviewer', applicationStatus: 'IN_REVIEW', createdAt: '2026-11-14T08:00:00Z', openNote: null },
      // the "server" sends a string where KycTaskRow has an int: the widget must say so
      { id: 'task-2', orgId: 'org-b', orgName: 'Rundu Fresh', kind: 'KYC_REVIEW', stageKey: 'ENTRY_CHECK', stageSeq: '1',
        actorRule: 'ORG_ADMIN:other', applicationStatus: 'ENTRY_CHECK', createdAt: '2026-11-13T08:00:00Z', openNote: null },
    ] } });
    if (/\/api\/kyc\/tasks\/[^/]+\/decision$/.test(url.pathname)) {
      writes.push({ headers: req.headers(), body: req.postDataJSON() });
      return r.fulfill({ json: { id: 'k-1', orgId: 'org-a', orgType: 'SUPPLIER', orgName: 'Etosha Traders', parentOrgId: null, treePath: [],
        depth: 1, isSuspended: false, status: 'APPROVED', application: null, submittedApplication: null, submittedBy: null,
        submittedAt: null, decidedBy: 'u-rev', decidedAt: '2026-11-15T10:00:00Z', decisionNote: null, version: 4, masked: false,
        canEdit: false, canSubmit: false, canDecide: false, createdAt: null, updatedAt: null } });
    }
    return r.fulfill({ status: 404, json: {} });
  });

  await page.goto('/w/kyc.tasks?data=live&tier=md&single=1&grid=0');
  await expect(page.locator('wb-connection')).toHaveAttribute('data-connection', 'connected');
  await expect(page.locator('wb-connection')).toContainText('reviewer@mipay');
  await expect(page.locator('mp-widget-host')).toHaveAttribute('data-widget-state', 'ready');
  await expect(page.locator('mp-data-table')).toContainText('Etosha Traders');

  // self-diagnosis of the live answer, in the widget
  const diag = page.locator('[data-diagnostics]');
  await expect(diag).toContainText('kyc.tasks.search');
  await expect(diag).toContainText('$.items[1].stageSeq expected integer, got "1" (string)');

  // a write: the gateway session's CSRF value, never a token
  await page.locator('mp-data-table tbody tr', { hasText: 'Etosha Traders' }).click();
  await page.locator('[data-action=decide] mp-button').last().click();
  await expect(page.locator('mp-alert')).toContainText('Approved: Etosha Traders is now Approved');
  expect(writes).toHaveLength(1);
  expect(writes[0].headers['x-csrf']).toBe('csrf-live-123');
  expect(writes[0].headers['authorization']).toBeUndefined();
  expect(writes[0].body).toEqual({ outcome: 'APPROVE', note: null });
  expect(seen.every((s) => s.url.startsWith('/api/') && !s.auth)).toBe(true);
  await page.screenshot({ path: 'test-results/live/kyc-tasks-live.png', fullPage: true });
});
