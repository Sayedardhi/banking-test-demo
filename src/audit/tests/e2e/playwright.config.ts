import { defineConfig, devices } from '@playwright/test';

const reportDir = process.env.E2E_REPORT_DIR ?? '.';

export default defineConfig({
  testDir: './specs',
  outputDir: `${reportDir}/test-results`,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // The outage test stops the shared audit container, so tests must not overlap.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: `${reportDir}/playwright-report` }],
    ['junit', { outputFile: `${reportDir}/junit.xml` }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    trace: (process.env.E2E_TRACE as 'on' | 'retain-on-failure' | undefined) ?? 'retain-on-failure',
    video: (process.env.E2E_VIDEO as 'on' | 'retain-on-failure' | undefined) ?? 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: { slowMo: Number(process.env.E2E_SLOWMO ?? 0) },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
