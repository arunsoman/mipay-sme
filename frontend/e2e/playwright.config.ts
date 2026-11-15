/**
 * Layout suite for registered widgets (docs/plans/portal-builder.md §10): every widget × grid
 * tier × fixture scenario, rendered by the workbench, measured for overflow and checked for the
 * scenario's expected state. Headless only, on the system Chrome (no browser download).
 *
 *   npm run test:layout                     # all widgets
 *   npm run test:layout -- -g kyc.application
 */
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.layout\.ts$/,
  timeout: 30_000,
  fullyParallel: true,
  workers: 4,
  // the dev server slows near the end of a full run; a retried pass is reported as "flaky", never hidden
  retries: 1,
  reporter: [['list'], ['html', { outputFolder: '../test-results/layout-report', open: 'never' }]],
  outputDir: '../test-results/layout',
  use: {
    baseURL: 'http://localhost:4300',
    channel: 'chrome',
    headless: true,
    viewport: { width: 1440, height: 1000 },
  },
  webServer: {
    command: 'npx ng serve widget-workbench --port 4300',
    url: 'http://localhost:4300',
    reuseExistingServer: true,
    timeout: 180_000,
  },
});
