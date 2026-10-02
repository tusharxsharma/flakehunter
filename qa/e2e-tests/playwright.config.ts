import { defineConfig, devices } from '@playwright/test'

const isCI = Boolean(process.env.CI)

/**
 * BASE_URL: where the dashboard is served (Vite dev server locally, nginx in docker compose).
 * API_URL:  the Java API, used to seed test data directly (much faster than clicking through a UI).
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: isCI,
  // One retry in CI; Playwright marks tests that pass on retry as "flaky" in its report,
  // and the JUnit output is uploaded to FlakeHunter itself (see .github/workflows/ci.yml).
  retries: isCI ? 1 : 0,
  workers: isCI ? 2 : undefined,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
  ],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] }, testMatch: /responsive\.spec\.ts/ },
  ],
})
