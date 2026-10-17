import { defineConfig } from '@playwright/test';

// The packaged desktop app's e2e (roadmap item 50). It runs inside a stock Ubuntu container
// (desktop/run-in-docker.ts), where the test launches the installed app itself: no backend
// of the suite's own, so no globalSetup.
export default defineConfig({
  testDir: './desktop-tests',
  workers: 1,
  reporter: 'list',
  timeout: 600_000,
  expect: { timeout: 20_000 },
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
