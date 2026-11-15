/**
 * Workbench behaviour (not one widget's layout): switching widgets in the sidebar keeps the
 * query string, so ?scenario= can name a scenario the new widget does not have. The page must
 * fall back to `default` instead of handing the widget an empty answer set (NO_FIXTURE).
 */
import { expect, test } from '@playwright/test';

test('a scenario carried over from another widget falls back to default', async ({ page }) => {
  // `masked` is a kyc.application scenario; users.organization-users has none by that name
  await page.goto('/w/users.organization-users?portal=SUPPLIER&scenario=masked&tier=md&single=1&grid=0');
  await page.waitForFunction(() => (window as any).__wb?.ready?.());
  await expect(page.locator('mp-widget-host')).toHaveAttribute('data-widget-state', 'ready', { timeout: 10_000 });
  await expect(page.getByText('NO_FIXTURE')).toHaveCount(0);
});

test('switching widgets in the sidebar keeps working', async ({ page }) => {
  await page.goto('/w/kyc.application?portal=SUPPLIER&scenario=masked&tier=md&single=1&grid=0');
  await page.waitForFunction(() => (window as any).__wb?.ready?.());
  await page.getByRole('link', { name: 'Organization users' }).click();
  await page.waitForFunction(() => (window as any).__wb?.ready?.());
  await expect(page.locator('mp-widget-host')).toHaveAttribute('data-widget-state', 'ready', { timeout: 10_000 });
  await expect(page.getByText('NO_FIXTURE')).toHaveCount(0);
});
