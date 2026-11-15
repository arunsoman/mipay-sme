import { expect, test } from '@playwright/test';

/**
 * The portal builder end to end (headless): open a submenu's empty screen, drag a registered
 * widget onto it, check it renders at every tier, add a locale and see the i18n checks react.
 * Screenshots land in test-results/builder/ for review.
 */
test('build a supplier screen with the KYC widget, in two locales', async ({ page }) => {
  await page.addInitScript(() => { try { localStorage.clear(); } catch { /* ignore */ } });
  await page.setViewportSize({ width: 1600, height: 1000 });
  await page.goto('/builder');
  const shot = (name: string) => page.screenshot({ path: `test-results/builder/${name}.png`, fullPage: true });

  // the palette offers what the registry has for SUPPLIER
  await expect(page.locator('.bd-top select').first()).toHaveValue('SUPPLIER');   // dropdowns show the real state
  const kyc = page.locator('.pal', { hasText: 'KYC application' });
  await expect(kyc).toBeVisible();

  // today's menus are seeded: open Onboarding › KYC and documents
  await page.locator('.ts', { hasText: 'KYC and documents' }).click();
  await expect(page.locator('.cv-empty')).toBeVisible();

  // drag the widget from the palette onto the empty screen
  const from = await kyc.boundingBox();
  const to = await page.locator('[data-canvas]').boundingBox();
  await page.mouse.move(from!.x + 30, from!.y + 15);
  await page.mouse.down();
  await page.mouse.move(from!.x + 45, from!.y + 30, { steps: 5 });
  await page.mouse.move(to!.x + to!.width / 2, to!.y + 80, { steps: 25 });
  await page.mouse.up();

  const host = page.locator('[data-canvas] mp-widget-host');
  await expect(host).toHaveAttribute('data-widget-state', 'ready', { timeout: 15_000 });
  await expect(page.locator('.cv-cell .cv-head')).toContainText('KYC application');
  await expect(page.locator('.cv-head .mono')).toHaveText('4/4');           // phone: full width
  await page.waitForTimeout(300);
  await shot('1-screen-phone');

  await page.locator('.tabs button', { hasText: 'Laptop' }).click();
  await expect(page.locator('.cv-head .mono')).toHaveText('12/12');         // laptop: span.md(8, 12) defaults to 12
  await page.locator('.cv-head button[title=Narrower]').click();
  await expect(page.locator('.cv-head .mono')).toHaveText('11/12');
  for (let i = 0; i < 5; i++) await page.locator('.cv-head button[title=Narrower]').click();
  await expect(page.locator('.cv-head .mono')).toHaveText('8/12');          // never below the widget's minimum
  await expect(page.locator('.bd-right')).toContainText('kyc.application:submit');  // the widget inspector
  await page.locator('.ts', { hasText: 'KYC and documents' }).click();             // the submenu inspector
  await expect(page.locator('.facts')).toContainText('kyc.application:read, kyc.form:read');
  await expect(page.locator('.facts')).toContainText('nav.supplier_onboarding_documents:read'); // legacy cell kept
  await shot('2-screen-laptop');

  // add Afrikaans: every label now misses a translation → errors, export blocked
  await expect(page.locator('.badge')).toContainText('0 error(s)');
  await page.locator('.locs select').selectOption('af');
  await expect(page.locator('.badge')).not.toContainText(' 0 error(s)');
  await page.locator('.tabs button', { hasText: 'Translations' }).click();
  const firstAf = page.locator('table.tr tbody tr').first().locator('td').nth(2).locator('input');
  await firstAf.fill('Tuis');
  await firstAf.press('Enter');
  await expect(firstAf).toHaveValue('Tuis');
  await shot('3-translations');

  await expect(page.locator('.bd-top label', { hasText: 'Preview in' }).locator('select')).toHaveValue('af');
  await page.locator('.tabs button', { hasText: 'Sidebar preview' }).click();
  await expect(page.locator('.sb-prev')).toContainText('Tuis');
  await expect(page.locator('.sb-prev')).toContainText('⚠ portal.supplier.');  // the gaps are visible
  await shot('4-sidebar-af');

  await page.locator('.tabs button', { hasText: 'Export' }).click();
  await expect(page.locator('.exp button').first()).toBeDisabled();
  await shot('5-export-blocked');
});
