import { defineConfig, devices } from '@playwright/test';

/**
 * Runs against the real packaged API and PostgreSQL, the production build of the web app, and a local stub of the job
 * source. Prerequisites: Docker, Java 21, and the two builds:
 *   cd backend  && ./mvnw -DskipTests package
 *   cd frontend && npx ng build
 */
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4300',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    permissions: ['clipboard-read', 'clipboard-write'],
    locale: 'en-US',
    ...devices['Desktop Chrome'],
  },
  webServer: [
    { command: 'node support/stub-jobs.mjs', url: 'http://127.0.0.1:4010/health', reuseExistingServer: false, timeout: 20_000 },
    { command: 'node support/start-api.mjs', url: 'http://127.0.0.1:8081/actuator/health', reuseExistingServer: false, timeout: 240_000 },
    { command: 'node support/serve.mjs', url: 'http://127.0.0.1:4300/', reuseExistingServer: false, timeout: 20_000 },
  ],
  globalTeardown: './support/teardown.ts',
});
