import { defineConfig } from '@playwright/test';

// The e2e suite (roadmap item 21) runs the real backend on a fresh temporary data dir
// (global-setup.mjs) and drives the built app served by that backend. One worker: the
// suite is one journey against one shared backend; sequential tests keep the uploaded
// state unambiguous.
export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.mjs',
  // Item 35's GIF recorder is not part of the journey: it runs standalone via
  // `pnpm record:gifs` (playwright.gifs.config.ts) against its own fresh backend.
  testIgnore: ['**/gifs-record.spec.ts'],
  workers: 1,
  reporter: 'list',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  use: {
    // The farm's chromium-headless-shell renderer hits a Blink NOTREACHED crash on the
    // app's self-hosted WOFF2 (font-face loading) — the full chromium build is unaffected.
    // Decided: found while wiring up this suite; pinned here so the tests run the stable
    // binary headlessly.
    channel: 'chromium',
    viewport: { width: 1280, height: 800 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
