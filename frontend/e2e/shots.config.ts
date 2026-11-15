/** Screenshots of one widget (SHOT_ID) at every tier, for `widgets:apply` (SHOT_OUT = output folder). */
import { defineConfig } from '@playwright/test';
import base from './playwright.config';

export default defineConfig({ ...base, testMatch: /\.shots\.ts$/, fullyParallel: false, workers: 1, reporter: 'line' });
