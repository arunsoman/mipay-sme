import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

const id = process.env['SHOT_ID'];
const out = process.env['SHOT_OUT'];
const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, '../projects/shared/widgets/widget-manifest.json'), 'utf8'));
const entry = manifest.widgets.find((w: { id: string }) => w.id === id);

test(`screenshots of ${id}`, async ({ page }) => {
  test.skip(!id || !out || !entry, 'SHOT_ID / SHOT_OUT not set or widget not registered');
  const portal = entry.contexts[entry.contexts.length - 1];
  for (const tier of ['xs', 'sm', 'md', 'lg']) {
    if (entry.size[tier].kind === 'hidden') continue;
    await page.setViewportSize({ width: 1600, height: 1000 });
    await page.goto(`/w/${id}?portal=${portal}&scenario=default&tier=${tier}&single=1&grid=0&outline=0`);
    await expect(page.locator('mp-widget-host')).not.toHaveAttribute('data-widget-state', 'loading', { timeout: 15_000 });
    await page.waitForTimeout(300);
    await page.locator('wb-frame').screenshot({ path: path.join(out!, `${id}.${tier}.png`) });
  }
  // all tiers side by side, as in the harness
  await page.setViewportSize({ width: 3500, height: 1200 });
  await page.goto(`/w/${id}?portal=${portal}&scenario=default&grid=0&outline=0`);
  await expect(page.locator('mp-widget-host').first()).not.toHaveAttribute('data-widget-state', 'loading', { timeout: 15_000 });
  await page.waitForTimeout(400);
  await page.locator('.frames').screenshot({ path: path.join(out!, `${id}.all-tiers.png`) });
});
