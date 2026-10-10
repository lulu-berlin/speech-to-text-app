import { defineConfig } from '@playwright/test';

// The e2e suite (roadmap item 21) runs the real backend on a fresh temporary data dir
// (global-setup.mjs) and drives the built app served by that backend. One worker: the
// suite is one journey against one shared backend; sequential tests keep the uploaded
// state unambiguous.
export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.mjs',
  workers: 1,
  reporter: 'list',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  use: {
    viewport: { width: 1280, height: 800 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
