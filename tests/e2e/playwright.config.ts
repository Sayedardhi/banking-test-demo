import { defineConfig, devices } from '@playwright/test';

const outputRoot = process.env.E2E_OUTPUT_DIR ?? 'test-results';

export default defineConfig({
  testDir: './specs',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: process.env.CI ? 2 : 3,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  outputDir: `${outputRoot}/artifacts`,
  reporter: [
    ['list'],
    ['html', { outputFolder: `${outputRoot}/playwright-report`, open: 'never' }],
    ['junit', { outputFile: `${outputRoot}/junit.xml` }],
    ['json', { outputFile: `${outputRoot}/results.json` }],
  ],
  use: {
    baseURL: process.env.FRONTEND_URL ?? 'http://localhost:18080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
