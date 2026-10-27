import { defineConfig } from '@playwright/test';

// The packaged desktop app's e2e (roadmap item 50). It runs inside a stock Ubuntu container
// (desktop/run-in-docker.ts), where the test launches the installed app itself: no backend
// of the suite's own, so no globalSetup.
export default defineConfig({
  testDir: './desktop-tests',
  // The suite's specs only: a helper's unit test (e.g. app-trace.test.ts) runs under
  // `node --test` and must not be loaded as a Playwright file in the packaged jobs.
  testMatch: '**/*.spec.ts',
  workers: 1,
  reporter: 'list',
  timeout: 600_000,
  expect: { timeout: 20_000 },
  // Roadmap item 82: the app window's trace (desktop-tests/helpers/app.ts) is written for every
  // spec; like `retain-on-failure`, only a failing spec's output directory keeps it.
  preserveOutput: 'failures-only',
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
