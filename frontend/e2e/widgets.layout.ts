import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

interface Entry { id: string; contexts: string[]; scenarios: Record<string, string>; size: Record<string, { kind: string }> }

const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, '../projects/shared/widgets/widget-manifest.json'), 'utf8'));
const TIERS = ['xs', 'sm', 'md', 'lg'] as const;

for (const w of manifest.widgets as Entry[]) {
  for (const tier of TIERS) {
    if (w.size[tier].kind === 'hidden') continue;
    for (const [scenario, expected] of Object.entries(w.scenarios)) {
      test(`${w.id} @${tier} ${scenario}`, async ({ page }, info) => {
        const url = `/w/${w.id}?portal=${w.contexts[w.contexts.length - 1]}&scenario=${scenario}&tier=${tier}&single=1&grid=0&outline=1`;
        info.annotations.push({ type: 'reproduce', description: `http://localhost:4300${url}` });
        await page.goto(url);
        await page.waitForFunction(() => (window as any).__wb?.ready?.());
        const host = page.locator('mp-widget-host');
        await expect(host).toHaveAttribute('data-widget-state', expected, { timeout: 10_000 });
        await page.waitForTimeout(150); // fonts / late layout

        const g = await page.evaluate((t) => (window as any).__wb.measure(t), tier);
        expect(g, 'the widget cell was measured').not.toBeNull();
        if (g.smallTargets.length) info.annotations.push({ type: 'tap targets < 44px', description: g.smallTargets.join(' | ') });
        await info.attach(`${w.id}-${tier}-${scenario}.png`, { body: await page.locator('wb-frame').screenshot(), contentType: 'image/png' });

        expect(g.overflowX, `horizontal overflow at ${tier} (${g.cellWidth}px)`).toEqual([]);
        expect(g.outsideCell, `content outside the cell at ${tier}`).toEqual([]);
      });
    }
  }
}
